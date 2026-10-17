# Open Authenticator

An independent, offline-first Android authenticator for standard time-based one-time passwords (TOTP). It follows the familiar authenticator workflow—scan or enter a setup key, then copy a rotating verification code—without using Google services or network access. This project is not affiliated with Google and does not reproduce Google's proprietary app or cloud-sync features.

## Features

- Generate 6- or 8-digit TOTP codes using SHA-1, SHA-256, or SHA-512.
- Add accounts by scanning a standard `otpauth://totp/...` QR code or entering the Base32 setup key.
- Keep every code masked by default behind a locked eye icon. Revealing a code requires a fresh front-camera face check; it stays visible for at most 30 seconds and hides again when the app leaves the foreground.
- Copying a code also requires a face check, so a hidden code cannot be read from the clipboard.
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

### Locked eye icon (per-code reveal gate)

Unlocking the app does **not** expose the verification codes. Every account card shows bullets in place of its digits and a crossed-out eye with a small padlock. Tapping the locked eye—or the card itself—opens the same on-device face screen in a dedicated reveal mode. A live camera frame must match the enrolled face descriptors before the current code is drawn. The eye then becomes an open eye, and tapping it again hides the code immediately.

A successful reveal lasts at most 30 seconds. It is also cleared as soon as the activity pauses (another app, screen off, the recents screen, a lock, or process death), so the next reveal always needs another face scan. Copying is blocked while the code is hidden. The backup PIN created during enrollment can be used instead of a face scan if the camera is unavailable or the scan keeps failing.

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

A dependency-free manual build script is also included. It requires `javac`, `aapt2`, `d8`, `apksigner`, `zipalign`, `zip`, `python3`, and an API 35 `android.jar`; set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `ANDROID_JAR`, `AAPT2`, `D8`, and `APKSIGNER` as needed:

```sh
./build.sh
```

The resulting APK is `Authenticator.apk` (version 1.2.0), which adds the per-code face-check reveal gate. The checked-in APK in this workspace is debug-signed for testing and sideloading; create a separate release key and signing configuration before distribution. Because a local build generates a fresh debug key by default, Android may refuse to update an earlier debug build in place; uninstalling it first also removes its saved accounts and enrolled face. Minimum Android version is API 23 (Android 6.0). If you use an older standalone Java compiler or D8, you can set `JAVA_BOOTCLASSPATH` and `D8_ANDROID_JAR` to an older installed platform jar; current Android Studio builds should use the configured API 35 platform directly.

## Android 6.0+ installation compatibility

The minimum supported version is **Android 6.0 (API 23)**; target SDK 35 does
not mean Android 15 is required. The manual build explicitly enables v1 signing
(required by Android 6), verifies signatures starting at API 23, and requires
ZIP alignment. A packaging check rejects newer DEX formats, missing v1 signing
entries, and compressed or unaligned resource tables. Gradle debug builds also
explicitly enable v1 signing. Release signing configurations must enable it too.

If installation still fails, capture the exact error (or use
`adb install -r Authenticator.apk` from a computer):

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the installed app uses a different
  signing key. Rebuild with the original keystore to update without losing data.
  **Do not uninstall or clear app data unless you have recovery codes or setup
  keys for every account**; doing so destroys the locally stored accounts.
- `INSTALL_FAILED_VERSION_DOWNGRADE`: install a build with a version code at
  least as high as the installed version.
- `INSTALL_FAILED_OLDER_SDK`: confirm the phone runs Android 6.0 or newer and
  that you are installing this project's APK, not another download.
- For blocked sideloads, allow installation from the app opening the APK
  (on Android 6/7, use Settings → Security → Unknown sources).

An actual device/emulator install is still needed to confirm device-specific
compatibility; packaging checks alone cannot establish why an installation failed.

## Tests

Run the APK packaging checks and their regression tests without an Android SDK:

```sh
python3 tests/check_apk_compatibility.py Authenticator.apk
python3 -m unittest discover -s tests -p 'test_*.py'
```

These check packaging, not signature authenticity; `build.sh` also uses
`apksigner verify --min-sdk-version 23` to verify the signatures cryptographically.


The dependency-free RFC 6238 regression vectors are in `tests/TotpVectors.java`, and the custom face descriptor checks are in `tests/FaceTemplateVectors.java`. After running `./build.sh`, run them with a JDK using the compiled app classes on the classpath:

```sh
mkdir -p build/test-classes
javac -cp build/manual/classes -d build/test-classes \
  tests/TotpVectors.java tests/FaceTemplateVectors.java
java -cp build/manual/classes:build/test-classes TotpVectors
java -cp build/manual/classes:build/test-classes FaceTemplateVectors
```

## Credits and technology used

The face unlock and per-code reveal gate are implemented from first principles on top of the Android platform. No third-party face-recognition SDK, pre-trained ML model, cloud service, fingerprint sensor, Android `BiometricPrompt`, or Google Play Services is used.

- **Android Camera API** (`android.hardware.Camera`) supplies the front-camera NV21 preview frames used for enrollment and for every unlock/reveal check.
- **Custom face descriptor** (`FaceTemplate.java`)—a 48×48 illumination-normalised face crop, a 6×6 grid of 8-bin histograms of oriented gradients, and a 24×24 low-frequency thumbnail. Matching uses cosine similarity with a ±1-cell/±1-pixel shift search, and requires two consecutive live reads above the match threshold.
- **Android Keystore with AES-256-GCM** (`FaceStore.java`)—the face descriptors and backup-PIN hash are encrypted at rest with a non-exportable key generated inside the Android Keystore.
- **PBKDF2-HMAC-SHA1**—60,000-iteration backup-PIN hashing with a random salt, plus persisted attempt throttling.
- **ZXing Core 3.5.3**—QR-code decoding. Licensed under Apache 2.0; see [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
- **RFC 4226 / RFC 6238**—HOTP/TOTP code generation in `Totp.java`.
- **Plain Android views and Canvas**—the eye/lock icon, countdown ring, face oval, and image-free UI are drawn with standard `Activity`, `View`, and `Canvas` APIs, with no UI framework dependency.

## Implementation and license

The TOTP implementation follows RFC 4226 and RFC 6238. This project is an independent clone of the familiar authenticator workflow; it is not affiliated with Google and contains no Google proprietary code. QR decoding uses ZXing Core 3.5.3, whose Apache 2.0 license is included in [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
