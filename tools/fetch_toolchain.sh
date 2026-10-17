#!/usr/bin/env bash
# Fetches a self-contained toolchain for ./build.sh on machines without an
# Android SDK.  Everything is written to build/toolchain (git-ignored) and
# build.sh picks it up automatically.
#
# Downloads, all from public package registries:
#   * jdk4py (PyPI)         - a Java 25 runtime (JRE) for ECJ and D8
#   * @drxiaozhi/minapk     - d8.jar, apksigner.jar, android.jar, ecj JAR
#   * aaptjs3               - the Linux/macOS/Windows aapt2 binary
#
# Usage:  tools/fetch_toolchain.sh
#         ./build.sh
#
# Set TOOLCHAIN_DIR to install somewhere else, or NO_TOOLCHAIN_ENV=1 to stop
# build.sh from sourcing build/toolchain/env.sh.  If a Java runtime is already
# on PATH it is used as-is; the rest is always fetched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${TOOLCHAIN_DIR:-$ROOT/build/toolchain}"
DOWNLOADS="$DEST/downloads"
WORK="$DEST/work"

MINAPK_VERSION="0.4.0"
MINAPK_SHA256="d22fccaba9a909cf02f196c91ca1e45ac9cb4b32c6e16cf0493182d26c071417"
AAPTJS3_VERSION="2.0.2"
AAPTJS3_SHA256="26031bd1acb577edce91675ae92daf63cf85795d4e2399333edeb994dd9197e9"
JDK4PY_VERSION="25.0.2.1"

fail() { echo "fetch_toolchain.sh: $*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

have python3 || fail "python3 is required."
have tar || fail "tar is required to unpack the npm packages."
have uname || fail "uname is required."
mkdir -p "$DEST" "$DOWNLOADS" "$WORK"

download() {
  local url="$1" output="$2" expected="$3"
  if [[ -f "$output" ]]; then
    python3 - "$output" "$expected" <<'PY'
import hashlib, sys
with open(sys.argv[1], "rb") as handle:
    digest = hashlib.sha256(handle.read()).hexdigest()
if digest != sys.argv[2]:
    sys.exit("cached file %s is corrupt; delete it and retry" % sys.argv[1])
PY
    echo "using cached $(basename "$output")"
    return 0
  fi
  echo "downloading $url"
  python3 - "$url" "$output" "$expected" <<'PY'
import hashlib, sys, urllib.request
url, output, expected = sys.argv[1], sys.argv[2], sys.argv[3]
with urllib.request.urlopen(url, timeout=120) as response:
    data = response.read()
digest = hashlib.sha256(data).hexdigest()
if digest != expected:
    sys.exit("checksum mismatch for %s: got %s, expected %s"
             % (url, digest, expected))
with open(output, "wb") as handle:
    handle.write(data)
PY
}

arch="$(uname -m)"
os_name="$(uname -s)"
case "$os_name" in
  Linux)  aapt_platform="linux" ;;
  Darwin) aapt_platform="darwin" ;;
  MINGW*|MSYS*|CYGWIN*) aapt_platform="win32" ;;
  *) fail "unsupported operating system: $os_name" ;;
esac
case "$arch" in
  x86_64|amd64|AMD64) aapt_arch="x64" ;;
  *) fail "aapt2 from aaptjs3 is available for x86_64 only; install the Android SDK build-tools and set AAPT2 instead (detected $arch)." ;;
esac

# --- D8, apksigner, android.jar and the Eclipse compiler --------------------
minapk="$DOWNLOADS/minapk-$MINAPK_VERSION.tgz"
download "https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-$MINAPK_VERSION.tgz" \
  "$minapk" "$MINAPK_SHA256"
rm -rf "$WORK/minapk"
mkdir -p "$WORK/minapk"
tar -xzf "$minapk" -C "$WORK/minapk" --strip-components=1
for file in d8.jar apksigner.jar android.jar ecj-3.45.0.jar; do
  [[ -f "$WORK/minapk/tools/$file" ]] || fail "minapk does not contain tools/$file"
  cp "$WORK/minapk/tools/$file" "$DEST/$file"
done
cp "$WORK/minapk/tools/ecj-3.45.0.jar" "$DEST/ecj.jar"

# --- aapt2 ------------------------------------------------------------------
aapttgz="$DOWNLOADS/aaptjs3-$AAPTJS3_VERSION.tgz"
download "https://registry.npmjs.org/aaptjs3/-/aaptjs3-$AAPTJS3_VERSION.tgz" \
  "$aapttgz" "$AAPTJS3_SHA256"
rm -rf "$WORK/aaptjs3"
mkdir -p "$WORK/aaptjs3"
tar -xzf "$aapttgz" -C "$WORK/aaptjs3" --strip-components=1
aapt_source="$WORK/aaptjs3/bin/$aapt_arch/$aapt_platform/aapt2"
[[ -f "$aapt_source" ]] || fail "aaptjs3 has no $aapt_platform/$aapt_arch aapt2"
cp "$aapt_source" "$DEST/aapt2"
chmod +x "$DEST/aapt2"

# --- Java runtime -----------------------------------------------------------
java_bin=""
if [[ -n "${JAVA:-}" && -x "${JAVA:-}" ]]; then
  java_bin="$JAVA"
elif have java; then
  java_bin="$(command -v java)"
else
  wheel="$DOWNLOADS/jdk4py-$JDK4PY_VERSION.whl"
  if [[ ! -f "$wheel" ]]; then
    echo "resolving a jdk4py wheel for this platform..."
    python3 - "$JDK4PY_VERSION" "$wheel" <<'PY'
import json, platform, sys, urllib.request
version, output = sys.argv[1], sys.argv[2]
data = json.load(urllib.request.urlopen(
    "https://pypi.org/pypi/jdk4py/%s/json" % version, timeout=120))
machine = platform.machine().lower()
if sys.platform.startswith("linux"):
    wanted = ("manylinux", "aarch64" if machine in ("aarch64", "arm64") else "x86_64")
elif sys.platform == "darwin":
    wanted = ("macosx", "arm64" if machine in ("arm64",) else "x86_64")
elif sys.platform == "win32":
    wanted = ("win_amd64",)
else:
    sys.exit("unsupported platform for jdk4py: %s" % sys.platform)
matches = [item for item in data["urls"]
           if all(token in item["filename"] for token in wanted)]
if not matches:
    sys.exit("no jdk4py wheel for %s/%s" % (sys.platform, machine))
wheel = sorted(matches, key=lambda item: item["filename"])[-1]
with urllib.request.urlopen(wheel["url"], timeout=300) as response:
    blob = response.read()
import hashlib
if hashlib.sha256(blob).hexdigest() != wheel["digests"]["sha256"]:
    sys.exit("checksum mismatch for %s" % wheel["filename"])
with open(output, "wb") as handle:
    handle.write(blob)
print("downloaded %s" % wheel["filename"])
PY
  fi
  rm -rf "$DEST/jdk4py"
  mkdir -p "$DEST/jdk4py"
  python3 -m zipfile -e "$wheel" "$DEST/jdk4py"
  # zipfile does not restore Unix permission bits; the JVM launcher and its
  # helper binary must be executable.
  chmod +x "$DEST/jdk4py/jdk4py/java-runtime/bin/"* 2>/dev/null || true
  [[ -f "$DEST/jdk4py/jdk4py/java-runtime/lib/jspawnhelper" ]] \
    && chmod +x "$DEST/jdk4py/jdk4py/java-runtime/lib/jspawnhelper" || true
  if [[ -x "$DEST/jdk4py/jdk4py/java-runtime/bin/java" ]]; then
    java_bin="$DEST/jdk4py/jdk4py/java-runtime/bin/java"
  else
    java_bin="$(find "$DEST/jdk4py" -path "*/java-runtime/bin/java" -type f -print -quit)"
  fi
  [[ -n "$java_bin" && -x "$java_bin" ]] || fail "the jdk4py wheel did not contain a Java runtime"
fi

# --- env file used by build.sh ---------------------------------------------
cat > "$DEST/env.sh" <<EOF
# Generated by tools/fetch_toolchain.sh. Explicit environment variables win.
export JAVA="\${JAVA:-$java_bin}"
export ECJ_JAR="\${ECJ_JAR:-$DEST/ecj.jar}"
export ANDROID_JAR="\${ANDROID_JAR:-$DEST/android.jar}"
export AAPT2="\${AAPT2:-$DEST/aapt2}"
export D8_JAR="\${D8_JAR:-$DEST/d8.jar}"
EOF

echo
echo "Toolchain ready in $DEST"
echo "  JAVA=$java_bin"
echo "  ECJ_JAR=$DEST/ecj.jar"
echo "  ANDROID_JAR=$DEST/android.jar"
echo "  AAPT2=$DEST/aapt2"
echo "  D8_JAR=$DEST/d8.jar"
echo
echo "Run ./build.sh; it sources $DEST/env.sh automatically."
