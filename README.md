# Open Authenticator

An independent, offline-first Android authenticator for standard time-based one-time passwords (TOTP). It follows the familiar authenticator workflow—scan or enter a setup key, then copy a rotating verification code—without using Google services or network access. This project is not affiliated with Google and does not reproduce Google's proprietary app or cloud-sync features.

## Features

- Generate 6- or 8-digit TOTP codes using SHA-1, SHA-256, or SHA-512.
- Add accounts by scanning a standard `otpauth://totp/...` QR code or entering the Base32 setup key.
- Automatically refresh codes and show the remaining time.
- Search, rename, remove, and copy account codes.
- Require a custom, on-device face registration before the account list is shown. Setup takes three front-camera samples and creates a six-digit backup PIN.
- Unlock with the front camera on every app open. The app does **not** use Android's biometric prompt, fingerprint sensors, Touch ID, or the device's built-in face unlock, and it does not request biometric permissions.
- Encrypt account data with AES-GCM using the Android Keystore; Android backup is disabled. Face descriptors and the PIN hash are separately encrypted with the Keystore.
- Work entirely offline. Camera permission is used to register/unlock with your face and to scan QR codes. Screenshots and the recents preview are blocked with `FLAG_SECURE` while the app is open.

Google migration/transfer QR codes, HOTP accounts, cloud sync, and account export are not supported. Keep a secure copy of each service's recovery codes before moving or uninstalling the app. Reinstalling the app removes its local Keystore key and saved accounts.

## Face unlock

The first time the app opens it requires face registration. Three guided front-camera samples are reduced to numeric descriptors, encrypted with a key in the Android Keystore, and stored only on the device. Camera images are never written to storage. A six-digit backup PIN is created at the same time and can be used if a scan fails in poor light or with different eyewear.

The lock is a **convenience gate, not security-grade biometrics**. It is implemented independently of the system biometric stack and is more easily spoofed than a dedicated biometric sensor (for example, with a photograph). The TOTP secrets themselves remain AES-GCM encrypted by the Android Keystore. If you forget both your face and your backup PIN, there is no recovery path; reinstall the app to start over, which also deletes the saved accounts.

A front-facing camera is required for initial setup. The app asks for camera permission when you register your face or scan a QR code; no storage or network permission is requested. Changing face registration or the backup PIN is only possible after the app has been unlocked.

## Demo QR code (test setup)

Use this sample to test the scan-and-add flow without connecting a real service. Open the app, complete its required face setup, then choose **Add account → Scan a QR code** and point the camera at this code on another screen or a printout.

![Demo TOTP account setup QR code](demo-totp-qr.png)

It creates a sample **Demo** account named `demo@example.com` with a 6-digit SHA-1 code that changes every 30 seconds. If you are testing on the same phone, choose **Add account → Enter a setup key**, then enter `demo@example.com` as the account name, `Demo` as the service, and `JBSWY3DPEHPK3PXP` as the key; the scanner reads from the live camera, not from image files.

**This setup key is public and only for testing. Never use it for a real account:** anyone with the QR code or key can generate the same codes.

## Build

Open this project in Android Studio, or use Gradle 8.10.2+ with Android SDK Platform 35 and Android Gradle Plugin 8.8.2 or newer:

```sh
gradle assembleDebug
```

A dependency-free manual build script is also included. It requires `javac`, `aapt2`, `d8`, `apksigner`, `zip`, and an API 35 `android.jar`; set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `ANDROID_JAR`, `AAPT2`, `D8`, and `APKSIGNER` as needed:

```sh
./build.sh
```

The resulting APK is `Authenticator.apk` (version 1.1.0). The checked-in APK in this workspace is debug-signed for testing and sideloading; create a separate release key and signing configuration before distribution. Because a local build generates a fresh debug key by default, Android may refuse to update an earlier debug build in place; uninstalling it first also removes its saved accounts and enrolled face. Minimum Android version is API 23 (Android 6.0). If you use an older standalone Java compiler or D8, you can set `JAVA_BOOTCLASSPATH` and `D8_ANDROID_JAR` to an older installed platform jar; current Android Studio builds should use the configured API 35 platform directly.

## Tests

The dependency-free RFC 6238 regression vectors are in `tests/TotpVectors.java`, and the custom face descriptor checks are in `tests/FaceTemplateVectors.java`. After running `./build.sh`, run them with a JDK using the compiled app classes on the classpath:

```sh
mkdir -p build/test-classes
javac -cp build/manual/classes -d build/test-classes \
  tests/TotpVectors.java tests/FaceTemplateVectors.java
java -cp build/manual/classes:build/test-classes TotpVectors
java -cp build/manual/classes:build/test-classes FaceTemplateVectors
```

## Implementation and license

The TOTP implementation follows RFC 4226 and RFC 6238. QR decoding uses ZXing Core 3.5.3, whose Apache 2.0 license is included in [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
