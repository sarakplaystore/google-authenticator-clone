# Open Authenticator

An independent, offline-first Android authenticator for standard time-based one-time passwords (TOTP). It follows the familiar authenticator workflow—scan or enter a setup key, then copy a rotating verification code—without using Google services or network access. This project is not affiliated with Google and does not reproduce Google's proprietary app or cloud-sync features.

## Features

- Generate 6- or 8-digit TOTP codes using SHA-1, SHA-256, or SHA-512.
- Add accounts by scanning a standard `otpauth://totp/...` QR code or entering the Base32 setup key.
- Keep every code masked by default behind a locked eye icon. Revealing a code requires a fresh front-camera face check; it stays visible for at most 30 seconds and hides again when the app leaves the foreground.
- Copying a code also requires a face check, so a hidden code cannot be read from the clipboard.
- Automatically refresh codes and show the remaining time.
- Search, rename, remove, and copy account codes.
- Require a custom, on-device face registration before the account list is shown. Setup auto-captures three guided angles (straight, your left, your right) plus a blink, and creates a six-digit backup PIN.
- Unlock with a live face check on every app open: three prompts (blink, turn left/right, nod) in a random order that changes every attempt, performed by the enrolled face — a photo or a replayed video cannot follow them. The app does **not** use Android's biometric prompt, fingerprint sensors, Touch ID, or the device's built-in face unlock, and it does not request biometric permissions.
- Fit any phone screen: the camera preview is full-screen edge-to-edge and centre-cropped (never stretched), the face guide is laid out in the measured free space, and notch/gesture-bar insets are honoured on every Android version.
- Encrypt account data with AES-GCM using the Android Keystore; Android backup is disabled. Face descriptors and the PIN hash are separately encrypted with the Keystore.
- Work entirely offline. Camera permission is used to register/unlock with your face and to scan QR codes. Screenshots and the recents preview are blocked with `FLAG_SECURE` while the app is open.

Google migration/transfer QR codes, HOTP accounts, cloud sync, and account export are not supported. Keep a secure copy of each service's recovery codes before moving or uninstalling the app. Reinstalling the app removes its local Keystore key and saved accounts.

## Face unlock

The first time the app opens it requires face registration. Three guided front-camera samples (straight, turned to your left, turned to your right — captured automatically when you hold each angle) and a blink are reduced to numeric descriptors, encrypted with a key in the Android Keystore, and stored only on the device. Camera images are never written to storage. A six-digit backup PIN is created at the same time and can be used if a scan fails in poor light or with different eyewear. Registrations made by older versions keep working.

A scan is a **live check, not just an image match**:

- Three challenges — blink, turn left, turn right, nod down — are drawn at random (with a `SecureRandom`) and asked in a random order, returning to centre between them. Every frame that counts towards a challenge must still match the enrolled face; turns must match the enrolled sample *for that side* and show the mouth moving further than the eyes (3D parallax a flat photo does not have, even when the photo itself is turned).
- The screen flashes at random times and the face must measurably brighten in response (skipped in already-bright surroundings). An emissive replay on another phone does not respond; frames showing the regular moiré grid of a photographed display are rejected outright.
- The platform face detector must see exactly one face, failed attempts pause face scanning for a cooldown (5 failures → 30 s, persisted like the PIN throttle), and the final confirmation requires both descriptor components to agree on three consecutive centred frames at a stricter threshold.

This is still a **camera-only check, not certified biometrics**: it is built so that photographs, printed cut-outs, and replayed videos fail, but it cannot match a dedicated infrared/depth sensor against a determined attacker with a realistic mask or a compromised device, and no camera-only check can. The TOTP secrets themselves remain AES-GCM encrypted by the Android Keystore. If you forget both your face and your backup PIN, there is no recovery path; reinstall the app to start over, which also deletes the saved accounts.

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

The resulting APK is `Authenticator.apk` (version 1.3.0), which hardens the face check with randomised liveness challenges and a fully responsive, edge-to-edge scan screen. The checked-in APK in this workspace is debug-signed for testing and sideloading; create a separate release key and signing configuration before distribution. Minimum Android version is API 23 (Android 6.0). If you use an older standalone Java compiler or D8, you can set `JAVA_BOOTCLASSPATH` and `D8_ANDROID_JAR` to an older installed platform jar; current Android Studio builds should use the configured API 35 platform directly.

### Signing identity

The project signs every build with the same certificate, which is checked in at
`tools/debug.keystore` (store and key password `android`, alias
`authenticator-debug`). This is deliberate: a build signed with a different
certificate cannot update an existing installation, and Android reports that
as `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. `tools/debug_key.pem` and
`tools/debug_cert.pem` hold the same key in PEM form for tools that cannot
read PKCS#12.

This is a **throwaway debug key, not a secret**. Its password is published in
this repository, so anyone can sign an APK that claims to be this app. Never
use it for a Play Store release; generate your own release key and keep it
private.

Because the key is stable, rebuilding the project and reinstalling the APK
updates the app in place and keeps the saved accounts and the enrolled face.

> **One-time step for existing installations.** The APK that was checked in
> before this fix was signed with a key that was never committed, so that key
> is gone. The repaired APK is therefore signed with a different certificate
> and cannot update that older build in place. If you already installed it,
> uninstall it once and install the new APK.
> **Uninstalling deletes the app's local data — the saved accounts and the
> enrolled face** — so save your account setup keys or recovery codes first
> and re-add the accounts afterwards. Every later update works in place.

## Android 6.0+ installation compatibility

The minimum supported version is **Android 6.0 (API 23)**; target SDK 35 does
not mean Android 15 is required. The manual build explicitly enables v1 signing
(required by Android 6), verifies signatures starting at API 23, and requires
ZIP alignment. Gradle debug builds also explicitly enable v1 signing. Release
signing configurations must enable it too.

The APK is signed with all three signature schemes at once:

- **v1 (JAR)** — the only scheme Android 6.0 understands.
- **v2** — required by Android 7.0 and newer.
- **v3** — used by Android 9.0 and newer.

A packaging check (`tests/check_apk_compatibility.py`) rejects newer DEX
formats, missing v1 signing entries, compressed or unaligned resource tables,
entries that are not 4-byte aligned, a v1 signature that does not cover the
actual entry contents, **a v2/v3 content digest that does not match the bytes
of the file**, **a v2/v3 signature that is not a valid RSA signature by the
certificate in the block**, and an APK that is not signed with the certificate
in `tools/debug_cert.pem`.

Both of the bolded checks are needed, because the two failure modes are
opposite. The APK this project shipped before this fix was signed and then its
ZIP container was rewritten — it was not 4-byte aligned and carried no
`zipalign` padding, so it never came out of the documented build pipeline. That
left the v2/v3 *content digest* stale, since the digest covers the ZIP layout
and not just the entry contents; the v1 signature, which hashes entry contents
only, still matched, which is why the file unzipped cleanly and passed every
earlier check. Android 7.0 and newer verify v2/v3 first and rejected the
package with `INSTALL_PARSE_FAILED_NO_CERTIFICATES` while the v1 signature
still looked perfectly valid. The digest check catches that. The signature
check catches the mirror image: a block that quotes the right digest but is not
actually signed by the key it claims — a corruption the digest check cannot
see, because the signature bytes live in the signing block, outside the data
the digest covers.

If installation still fails, capture the exact error (or use
`adb install -r Authenticator.apk` from a computer):

- `INSTALL_PARSE_FAILED_NO_CERTIFICATES`: the signature does not match the
  file. Run `apksigner verify --min-sdk-version 23 Authenticator.apk` and
  `python3 tests/check_apk_compatibility.py Authenticator.apk`; the packaging
  check reports the offending part.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the installed app uses a different
  signing key. Rebuild with `tools/debug.keystore` to update without losing
  data. **Do not uninstall or clear app data unless you have recovery codes or
  setup keys for every account**; doing so destroys the locally stored
  accounts.
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

These check packaging, the consistency of the signature with the file
contents, and the signature itself: the v2/v3 signature bytes are verified as
RSASSA-PKCS1-v1_5 signatures by the certificate in the signing block, so a
corrupted or fabricated signature is rejected even when the content digest it
quotes is correct. `build.sh` additionally uses
`apksigner verify --min-sdk-version 23` to verify the signatures
cryptographically.

`tools/apk_sign.py` is a dependency-free signer (v1 + v2 + v3) used to
reproduce and repair the checked-in APK when no Android SDK is available:

```sh
python3 tools/apk_sign.py --key tools/debug_key.pem --cert tools/debug_cert.pem \
  --out Authenticator.apk Authenticator.apk
```

It re-aligns every entry to 4 bytes and regenerates all three signature
schemes. Prefer `apksigner` for real releases; this tool exists so that the
signature of the checked-in artifact can be produced and verified with
nothing but Python.


The dependency-free RFC 6238 regression vectors are in `tests/TotpVectors.java`, the custom face descriptor checks are in `tests/FaceTemplateVectors.java`, and the anti-spoofing session checks (a synthetic face driven through the randomised prompts, plus static-photo, rigid-photo, screen-replay, impostor, and no-light-response attacks) are in `tests/LivenessVectors.java`. After running `./build.sh`, run them with a JDK using the compiled app classes on the classpath:

```sh
mkdir -p build/test-classes
javac -cp build/manual/classes -d build/test-classes \
  tests/TotpVectors.java tests/FaceTemplateVectors.java tests/LivenessVectors.java
java -cp build/manual/classes:build/test-classes TotpVectors
java -cp build/manual/classes:build/test-classes FaceTemplateVectors
java -cp build/manual/classes:build/test-classes LivenessVectors
```

## Credits and technology used

The face unlock and per-code reveal gate are implemented from first principles on top of the Android platform. No third-party face-recognition SDK, pre-trained ML model, cloud service, fingerprint sensor, Android `BiometricPrompt`, or Google Play Services is used.

- **Android Camera API** (`android.hardware.Camera`) supplies the front-camera NV21 preview frames used for enrollment and for every unlock/reveal check, and the platform **`android.media.FaceDetector`** vouches for face presence (exactly one face) on top of the app's own landmark geometry.
- **Custom face descriptor** (`FaceTemplate.java`)—a 48×48 illumination-normalised face crop, a 6×6 grid of 8-bin histograms of oriented gradients, and a 24×24 low-frequency thumbnail. Matching uses cosine similarity with a ±1-cell/±1-pixel shift search; the final confirmation additionally requires both components to clear independent floors.
- **Custom liveness engine** (`FaceSignals.java`, `LivenessSession.java`)—centre-surround eye localisation on a 96×96 crop, blink-openness and brow-jump detection, mouth/eye parallax, screen-moiré periodicity analysis, randomised screen-flash light response, and a pure-Java challenge–response state machine that fuses all of it with identity on every frame.
- **Android Keystore with AES-256-GCM** (`FaceStore.java`)—the face descriptors and backup-PIN hash are encrypted at rest with a non-exportable key generated inside the Android Keystore.
- **PBKDF2-HMAC-SHA1**—60,000-iteration backup-PIN hashing with a random salt, plus persisted attempt throttling.
- **ZXing Core 3.5.3**—QR-code decoding. Licensed under Apache 2.0; see [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
- **RFC 4226 / RFC 6238**—HOTP/TOTP code generation in `Totp.java`.
- **Plain Android views and Canvas**—the eye/lock icon, countdown ring, face oval, and image-free UI are drawn with standard `Activity`, `View`, and `Canvas` APIs, with no UI framework dependency.

## Implementation and license

The TOTP implementation follows RFC 4226 and RFC 6238. This project is an independent clone of the familiar authenticator workflow; it is not affiliated with Google and contains no Google proprietary code. QR decoding uses ZXing Core 3.5.3, whose Apache 2.0 license is included in [`ZXING-LICENSE.txt`](ZXING-LICENSE.txt). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
