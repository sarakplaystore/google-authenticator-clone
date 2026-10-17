# Open Authenticator

An independent, offline-first Android authenticator for standard time-based one-time passwords (TOTP). It follows the familiar authenticator workflow—scan or enter a setup key, then copy a rotating verification code—without using Google services or network access. This project is not affiliated with Google and does not reproduce Google's proprietary app or cloud-sync features.

## Features

- Generate 6- or 8-digit TOTP codes using SHA-1, SHA-256, or SHA-512.
- Add accounts by scanning a standard `otpauth://totp/...` QR code or entering the Base32 setup key.
- Automatically refresh codes and show the remaining time.
- Search, rename, remove, and copy account codes.
- Encrypt account data with AES-GCM using the Android Keystore; Android backup is disabled.
- Lock the code list with a face unlock that the app checks itself, on the device, with the front camera.
- Work entirely offline. The app requests camera permission only when scanning a QR code or when a face unlock is used.

## Face unlock

The app can ask for your face before it shows any code. The setting lives in *Settings → Face unlock* and works like this:

- The front camera takes a few pictures of your face while you centre it in the frame.
- Each picture is reduced to a short description of the face: a contrast-normalised intensity grid, gradient histograms and a coarse map of the proportions. The pictures themselves are never stored.
- The descriptions are encrypted with AES-GCM using the Android Keystore and stay on the device.
- Every time the app is opened, the camera picture is described the same way and compared with the stored descriptions on the device. Two matching pictures in a row unlock the app.
- The app locks again when it has been in the background for a while. The delay is configurable in *Settings → Lock again after*.

The face recognition of Android is not used, no network connection is made and nothing leaves the device. If the camera is not available or the face is not recognised, the app offers the PIN, pattern or password of the device as a fallback, and the face lock can be turned off again from the lock screen. The matching threshold and the behaviour of the descriptor are covered by `tests/FaceTemplateVectors.java`.

Google migration/transfer QR codes, HOTP accounts, cloud sync, and account export are not supported. Keep a secure copy of each service's recovery codes before moving or uninstalling the app. Reinstalling the app removes its local Keystore key and saved accounts.

## Build

Open this project in Android Studio, or use Gradle 8.10.2+ with Android SDK Platform 35 and Android Gradle Plugin 8.8.2 or newer:

```sh
gradle assembleDebug
```

A dependency-free manual build script is also included. It requires `javac`, `aapt2`, `d8`, `apksigner`, `zip`, and an API 35 `android.jar`; set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `ANDROID_JAR`, `AAPT2`, `D8`, and `APKSIGNER` as needed:

```sh
./build.sh
```

The resulting APK is `Authenticator.apk`. The checked-in APK in this workspace is debug-signed for testing and sideloading; create a separate release key and signing configuration before distribution. The debug key is generated on the machine that builds the APK, so an APK that was built elsewhere carries a different signature: uninstall the earlier build before sideloading this one, otherwise Android refuses the update. Minimum Android version is API 23 (Android 6.0). If you use an older standalone Java compiler or D8, you can set `JAVA_BOOTCLASSPATH` and `D8_ANDROID_JAR` to an older installed platform jar; current Android Studio builds should use the configured API 35 platform directly.

## Tests

The dependency-free RFC 6238 regression vectors are in `tests/TotpVectors.java`, the face matching checks are in `tests/FaceTemplateVectors.java`. After running `./build.sh`, run them with a JDK using the compiled app classes on the classpath:

```sh
mkdir -p build/test-classes
javac -cp build/manual/classes -d build/test-classes tests/TotpVectors.java tests/FaceTemplateVectors.java
java -cp build/manual/classes:build/test-classes TotpVectors
java -cp build/manual/classes:build/test-classes FaceTemplateVectors
```

## Implementation and license

The TOTP implementation follows RFC 4226 and RFC 6238. The face unlock is implemented in this repository without any machine learning library, using the camera and a descriptor that is described in `app/src/main/java/app/openauth/authenticator/FaceTemplate.java`. QR decoding uses ZXing Core 3.5.3, whose Apache 2.0 license is included in [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
