#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/manual}"
OUTPUT_APK="${OUTPUT_APK:-$ROOT/Authenticator.apk}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JAVA_BIN="${JAVA:-java}"
JAVA_BOOTCLASSPATH="${JAVA_BOOTCLASSPATH:-${ANDROID_JAR:-}}"

fail() { echo "build.sh: $*" >&2; exit 1; }
find_sdk_file() {
  local relative="$1"
  if [[ -n "$ANDROID_HOME" && -f "$ANDROID_HOME/$relative" ]]; then
    printf '%s\n' "$ANDROID_HOME/$relative"
    return 0
  fi
  return 1
}
find_build_tool() {
  local relative="$1"
  local -a candidates
  local i
  [[ -n "$ANDROID_HOME" ]] || return 1
  candidates=("$ANDROID_HOME"/build-tools/*/"$relative")
  for ((i=${#candidates[@]} - 1; i >= 0; i--)); do
    if [[ -x "${candidates[$i]}" ]]; then
      printf '%s\n' "${candidates[$i]}"
      return 0
    fi
  done
  return 1
}
find_build_file() {
  local relative="$1"
  local -a candidates
  local i
  [[ -n "$ANDROID_HOME" ]] || return 1
  candidates=("$ANDROID_HOME"/build-tools/*/"$relative")
  for ((i=${#candidates[@]} - 1; i >= 0; i--)); do
    if [[ -f "${candidates[$i]}" ]]; then
      printf '%s\n' "${candidates[$i]}"
      return 0
    fi
  done
  return 1
}

if [[ -z "${ANDROID_JAR:-}" ]]; then
  ANDROID_JAR="$(find_sdk_file platforms/android-35/android.jar || true)"
fi
[[ -n "${ANDROID_JAR:-}" && -f "$ANDROID_JAR" ]] || fail "set ANDROID_JAR to an Android API 35 android.jar (or set ANDROID_HOME)."
JAVA_BOOTCLASSPATH="${JAVA_BOOTCLASSPATH:-$ANDROID_JAR}"
[[ -f "$JAVA_BOOTCLASSPATH" ]] || fail "JAVA_BOOTCLASSPATH must point to an Android platform android.jar."

if [[ -z "${AAPT2:-}" ]]; then
  AAPT2="$(find_build_tool aapt2 || command -v aapt2 || true)"
fi
[[ -n "${AAPT2:-}" && -x "$AAPT2" ]] || fail "set AAPT2 to the Android aapt2 executable."

if [[ -z "${ZIPALIGN:-}" ]]; then
  ZIPALIGN="$(find_build_tool zipalign || command -v zipalign || true)"
fi

if [[ -n "${JAVAC:-}" ]]; then
  JAVAC_CMD=("$JAVAC")
elif command -v javac >/dev/null 2>&1; then
  JAVAC_CMD=("$(command -v javac)")
elif [[ -n "${JAVAC_JAR:-}" ]]; then
  JAVAC_CMD=("$JAVA_BIN" -cp "$JAVAC_JAR" com.sun.tools.javac.Main)
else
  fail "Java compiler not found. Install a JDK or set JAVAC/JAVAC_JAR."
fi

if [[ -n "${D8_JAR:-}" ]]; then
  D8_CMD=("$JAVA_BIN" -Xmx2g -cp "$D8_JAR" com.android.tools.r8.D8)
elif [[ -n "${D8:-}" ]]; then
  D8_CMD=("$D8")
else
  D8_PATH="$(find_build_tool d8 || command -v d8 || true)"
  [[ -n "$D8_PATH" ]] || fail "D8 not found. Set D8_JAR or D8 to the Android dex compiler."
  D8_CMD=("$D8_PATH")
fi

if [[ -n "${APKSIGNER_JAR:-}" ]]; then
  SIGN_CMD=("$JAVA_BIN" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool)
elif [[ -n "${APKSIGNER:-}" ]]; then
  SIGN_CMD=("$APKSIGNER")
else
  APKSIGNER_PATH="$(find_build_file lib/apksigner.jar || true)"
  if [[ -n "$APKSIGNER_PATH" ]]; then
    SIGN_CMD=("$JAVA_BIN" -cp "$APKSIGNER_PATH" com.android.apksigner.ApkSignerTool)
  elif command -v apksigner >/dev/null 2>&1; then
    SIGN_CMD=("$(command -v apksigner)")
  else
    fail "apksigner not found. Set APKSIGNER_JAR or APKSIGNER."
  fi
fi

if [[ -n "${KEYTOOL:-}" ]]; then
  KEYTOOL_BIN="$KEYTOOL"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL_BIN="$(command -v keytool)"
else
  fail "keytool not found; install a JDK."
fi
command -v zip >/dev/null 2>&1 || fail "zip is required to package classes.dex."

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex" "$(dirname "$OUTPUT_APK")"
rm -rf "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex"

"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD_DIR/resources.zip"
sed 's#<manifest xmlns:android=#<manifest package="app.openauth.authenticator" xmlns:android=#' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"
"$AAPT2" link \
  -o "$BUILD_DIR/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" \
  --min-sdk-version 23 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 1.0.0 \
  --auto-add-overlay \
  -R "$BUILD_DIR/resources.zip"

mapfile -d '' SOURCES < <(find "$ROOT/app/src/main/java" -name '*.java' -print0; find "$BUILD_DIR/generated" -name 'R.java' -print0)
((${#SOURCES[@]} > 0)) || fail "no Java sources found."
printf 'Compiling %s Java source files...\n' "${#SOURCES[@]}"
"${JAVAC_CMD[@]}" -source 8 -target 8 -encoding UTF-8 -g \
  -bootclasspath "$JAVA_BOOTCLASSPATH" -d "$BUILD_DIR/classes" "${SOURCES[@]}"

(
  cd "$BUILD_DIR/classes"
  zip -q -r "$BUILD_DIR/classes.jar" .
)
D8_ANDROID_JAR="${D8_ANDROID_JAR:-$ANDROID_JAR}"
"${D8_CMD[@]}" --release --min-api 23 --lib "$D8_ANDROID_JAR" \
  --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
[[ -f "$BUILD_DIR/dex/classes.dex" ]] || fail "D8 did not produce classes.dex."

cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
(
  cd "$BUILD_DIR/dex"
  zip -q -0 "$BUILD_DIR/unsigned.apk" classes.dex
)

APK_TO_SIGN="$BUILD_DIR/unsigned.apk"
if [[ -n "${ZIPALIGN:-}" && -x "$ZIPALIGN" ]]; then
  "$ZIPALIGN" -f 4 "$APK_TO_SIGN" "$BUILD_DIR/aligned.apk"
  APK_TO_SIGN="$BUILD_DIR/aligned.apk"
fi

KEYSTORE="${KEYSTORE:-$BUILD_DIR/debug.keystore}"
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$KEYTOOL_BIN" -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias authenticator-debug -dname "CN=Open Authenticator Debug" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"${SIGN_CMD[@]}" sign \
  --ks "$KEYSTORE" --ks-key-alias authenticator-debug \
  --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false \
  --out "$OUTPUT_APK" "$APK_TO_SIGN"
rm -f "$OUTPUT_APK.idsig"
"${SIGN_CMD[@]}" verify --verbose "$OUTPUT_APK"
printf '\nBuilt APK: %s\n' "$OUTPUT_APK"
printf 'Package: app.openauth.authenticator | min SDK: 23 | target SDK: 35\n'
printf 'Note: this APK is debug-signed for local testing/sideloading.\n'
