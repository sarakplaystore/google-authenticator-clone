package app.openauth.authenticator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Encrypted-at-rest storage for the locally enrolled face templates. Face
 * descriptors never leave the device and are protected by a key held in the
 * Android Keystore.
 *
 * <p>The six-digit backup PIN was removed from the app. Data written by an
 * older version is migrated in place: the leftover PIN hash is dropped the
 * next time the payload is read, and its throttling state is cleared when
 * this store is created.</p>
 */
public final class FaceStore {
  private static final String PREFS = "face_lock_private";
  private static final String DATA = "encrypted_face_v1";
  private static final String KEY_ALIAS = "openauthenticator.face.v1";
  private static final String FACE_FAILURES = "face_failures";
  private static final String FACE_BLOCKED_UNTIL = "face_blocked_until";
  /** Set once the state belonging to the removed backup PIN has been erased. */
  private static final String LEGACY_PIN_CLEANED = "backup_pin_removed";
  /** Preference keys written by builds that still had a backup PIN. */
  private static final String[] LEGACY_PIN_PREFERENCES = {
      "pin_failures", "pin_blocked_until", "invalid_face_scans", "pin_removed_by_failures"
  };
  private static final int MAX_FACE_FAILURES = 5;
  private static final long FACE_COOLDOWN_MS = 30000L;
  private static final String FORMAT_VERSION = "1";
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private final SharedPreferences preferences;

  public FaceStore(Context context) {
    preferences = context.getApplicationContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    removeLegacyPinState();
  }

  /**
   * True once face templates exist. The backup PIN never decided whether the
   * app was enrolled: a successful live scan is the only way in.
   */
  public synchronized boolean isEnrolled() {
    try {
      Payload payload = read();
      return payload != null && !payload.templates.isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public synchronized List<FaceTemplate> loadTemplates() {
    Payload payload = read();
    if (payload == null) return new ArrayList<FaceTemplate>();
    return new ArrayList<FaceTemplate>(payload.templates);
  }

  /** Stores a finished face registration, replacing any earlier templates. */
  public synchronized void saveTemplates(List<FaceTemplate> templates) {
    if (templates == null || templates.isEmpty()) {
      throw new IllegalArgumentException("Face registration did not finish.");
    }
    Payload payload = read();
    if (payload == null) payload = new Payload();
    payload.templates.clear();
    payload.templates.addAll(templates);
    write(payload);
  }

  /**
   * Face attempts are throttled: after five failed scans (timeouts, wrong
   * faces, or suspected spoofs) the camera scan pauses for a cooldown.
   * Persisted so force-stopping the app does not reset it. Walking away
   * mid-scan does not count as a failure.
   */
  public synchronized boolean isFaceBlocked() {
    long until = preferences.getLong(FACE_BLOCKED_UNTIL, 0L);
    if (until == 0L) return false;
    if (System.currentTimeMillis() >= until) {
      preferences.edit().remove(FACE_BLOCKED_UNTIL).remove(FACE_FAILURES).commit();
      return false;
    }
    return true;
  }

  public synchronized int faceCooldownSeconds() {
    if (!isFaceBlocked()) return 0;
    long remaining = preferences.getLong(FACE_BLOCKED_UNTIL, 0L) - System.currentTimeMillis();
    return Math.max(1, (int) Math.ceil(remaining / 1000.0));
  }

  /** Records one failed face attempt towards the short scan cooldown. */
  public synchronized void recordFaceFailure() {
    if (isFaceBlocked()) return;
    int failures = preferences.getInt(FACE_FAILURES, 0) + 1;
    if (failures >= MAX_FACE_FAILURES) {
      preferences.edit()
          .putLong(FACE_BLOCKED_UNTIL, System.currentTimeMillis() + FACE_COOLDOWN_MS)
          .remove(FACE_FAILURES)
          .commit();
    } else {
      preferences.edit().putInt(FACE_FAILURES, failures).commit();
    }
  }

  /** A successful authentication resets the cooldown. */
  public synchronized void clearFaceFailures() {
    preferences.edit()
        .remove(FACE_FAILURES)
        .remove(FACE_BLOCKED_UNTIL)
        .commit();
  }

  /**
   * The backup PIN is gone. Clear any PIN throttling state left by an older
   * install so it cannot accumulate forever. The stored hash itself is
   * dropped by {@link #read()} when the encrypted payload is next opened.
   */
  private void removeLegacyPinState() {
    if (preferences.getBoolean(LEGACY_PIN_CLEANED, false)) return;
    SharedPreferences.Editor editor = preferences.edit()
        .putBoolean(LEGACY_PIN_CLEANED, true);
    for (String key : LEGACY_PIN_PREFERENCES) {
      editor.remove(key);
    }
    editor.apply();
  }

  private Payload read() {
    String stored = preferences.getString(DATA, null);
    if (stored == null || stored.length() == 0) return null;
    try {
      byte[] cleartext = decrypt(stored);
      JSONObject root = new JSONObject(new String(cleartext, UTF8));
      if (root.optInt("version", 0) != 1) {
        throw new IllegalStateException("Unknown face data version.");
      }
      Payload payload = new Payload();
      JSONArray array = root.optJSONArray("templates");
      if (array != null) {
        for (int i = 0; i < array.length(); i++) {
          FaceTemplate template = FaceTemplate.fromBytes(
              Base64.decode(array.getString(i), Base64.DEFAULT));
          if (template != null) payload.templates.add(template);
        }
      }
      if (root.has("pin_salt") || root.has("pin_hash") || root.has("pin_iterations")) {
        // An older version stored a backup-PIN hash here. The PIN feature is
        // gone, so rewrite the payload without it. A failed rewrite must not
        // make the enrolled face unreadable; the next read retries.
        try {
          write(payload);
        } catch (RuntimeException ignored) {
          // Best-effort migration only.
        }
      }
      return payload;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Face settings could not be read on this device.", e);
    }
  }

  private void write(Payload payload) {
    try {
      JSONObject root = new JSONObject();
      root.put("version", 1);
      JSONArray array = new JSONArray();
      for (FaceTemplate template : payload.templates) {
        array.put(Base64.encodeToString(template.toBytes(), Base64.NO_WRAP));
      }
      root.put("templates", array);
      String encrypted = encrypt(root.toString().getBytes(UTF8));
      if (!preferences.edit().putString(DATA, encrypted).commit()) {
        throw new IllegalStateException("Face settings could not be saved.");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Face settings could not be saved securely.", e);
    }
  }

  private byte[] decrypt(String value) throws Exception {
    String[] parts = value.split(":", 3);
    if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
      throw new IllegalArgumentException("Unknown encrypted face format.");
    }
    byte[] iv = Base64.decode(parts[1], Base64.DEFAULT);
    byte[] ciphertext = Base64.decode(parts[2], Base64.DEFAULT);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
    return cipher.doFinal(ciphertext);
  }

  private String encrypt(byte[] cleartext) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
    byte[] iv = cipher.getIV();
    byte[] ciphertext = cipher.doFinal(cleartext);
    return FORMAT_VERSION + ":" + Base64.encodeToString(iv, Base64.NO_WRAP)
        + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
  }

  private SecretKey getOrCreateKey() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    if (keyStore.containsAlias(KEY_ALIAS)) {
      return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore");
    KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .build();
    generator.init(spec);
    return generator.generateKey();
  }

  private static final class Payload {
    final List<FaceTemplate> templates = new ArrayList<FaceTemplate>();
  }
}
