package app.openauth.authenticator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Encrypted-at-rest storage for the locally enrolled face templates and the
 * backup PIN. Face descriptors never leave the device and are protected by a
 * key held in the Android Keystore.
 */
public final class FaceStore {
  private static final String PREFS = "face_lock_private";
  private static final String DATA = "encrypted_face_v1";
  private static final String KEY_ALIAS = "openauthenticator.face.v1";
  private static final String PIN_FAILURES = "pin_failures";
  private static final String PIN_BLOCKED_UNTIL = "pin_blocked_until";
  private static final int MAX_PIN_FAILURES = 5;
  private static final long PIN_COOLDOWN_MS = 30000L;
  private static final String FORMAT_VERSION = "1";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final int PIN_ITERATIONS = 60000;
  private static final int PIN_BITS = 128;
  private static final int SALT_BYTES = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SharedPreferences preferences;

  public FaceStore(Context context) {
    preferences = context.getApplicationContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public synchronized boolean isEnrolled() {
    try {
      Payload payload = read();
      return payload != null && !payload.templates.isEmpty() && payload.pinHash != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public synchronized boolean hasPin() {
    try {
      Payload payload = read();
      return payload != null && payload.pinHash != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public synchronized List<FaceTemplate> loadTemplates() {
    Payload payload = read();
    if (payload == null) return new ArrayList<FaceTemplate>();
    return new ArrayList<FaceTemplate>(payload.templates);
  }

  public synchronized void saveEnrollment(List<FaceTemplate> templates, String pin) {
    if (templates == null || templates.isEmpty()) {
      throw new IllegalArgumentException("Face registration did not finish.");
    }
    String problem = pinProblem(pin);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    Payload payload = new Payload();
    payload.templates.clear();
    payload.templates.addAll(templates);
    setPin(payload, pin);
    write(payload);
  }

  public synchronized void saveTemplates(List<FaceTemplate> templates) {
    if (templates == null || templates.isEmpty()) {
      throw new IllegalArgumentException("Face registration did not finish.");
    }
    Payload payload = read();
    if (payload == null || payload.pinHash == null) {
      throw new IllegalStateException("No backup PIN is set up yet.");
    }
    payload.templates.clear();
    payload.templates.addAll(templates);
    write(payload);
  }

  public synchronized void updatePin(String pin) {
    String problem = pinProblem(pin);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    Payload payload = read();
    if (payload == null || payload.templates.isEmpty()) {
      throw new IllegalStateException("Register your face before setting a PIN.");
    }
    setPin(payload, pin);
    write(payload);
  }

  public synchronized boolean verifyPin(String pin) {
    if (pin == null) return false;
    Payload payload = read();
    if (payload == null || payload.pinHash == null || payload.pinSalt == null) return false;
    byte[] candidate = hashPin(pin, payload.pinSalt, payload.pinIterations);
    return MessageDigest.isEqual(candidate, payload.pinHash);
  }

  /** PIN throttling is persisted so force-stopping the app does not reset it. */
  public synchronized boolean isPinBlocked() {
    long until = preferences.getLong(PIN_BLOCKED_UNTIL, 0L);
    if (until == 0L) return false;
    if (System.currentTimeMillis() >= until) {
      preferences.edit().remove(PIN_BLOCKED_UNTIL).remove(PIN_FAILURES).commit();
      return false;
    }
    return true;
  }

  public synchronized int pinCooldownSeconds() {
    if (!isPinBlocked()) return 0;
    long remaining = preferences.getLong(PIN_BLOCKED_UNTIL, 0L) - System.currentTimeMillis();
    return Math.max(1, (int) Math.ceil(remaining / 1000.0));
  }

  public synchronized void recordPinFailure() {
    if (isPinBlocked()) return;
    int failures = preferences.getInt(PIN_FAILURES, 0) + 1;
    if (failures >= MAX_PIN_FAILURES) {
      preferences.edit()
          .putLong(PIN_BLOCKED_UNTIL, System.currentTimeMillis() + PIN_COOLDOWN_MS)
          .remove(PIN_FAILURES)
          .commit();
    } else {
      preferences.edit().putInt(PIN_FAILURES, failures).commit();
    }
  }

  public synchronized void clearPinFailures() {
    preferences.edit().remove(PIN_FAILURES).remove(PIN_BLOCKED_UNTIL).commit();
  }

  /** Returns an explanation when a PIN is weak or malformed, or null when it is fine. */
  public static String pinProblem(String pin) {
    if (pin == null || pin.length() != 6) {
      return "Enter a 6-digit PIN.";
    }
    boolean allSame = true;
    boolean ascending = true;
    boolean descending = true;
    for (int i = 0; i < pin.length(); i++) {
      char ch = pin.charAt(i);
      if (ch < '0' || ch > '9') {
        return "Your PIN can only contain numbers.";
      }
      if (i > 0) {
        char previous = pin.charAt(i - 1);
        if (ch != previous) allSame = false;
        if (ch != previous + 1) ascending = false;
        if (ch != previous - 1) descending = false;
      }
    }
    if (allSame) {
      return "Choose a PIN that is not the same digit repeated.";
    }
    if (ascending || descending) {
      return "Avoid counting sequences for your PIN.";
    }
    return null;
  }

  private static void setPin(Payload payload, String pin) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    payload.pinSalt = salt;
    payload.pinIterations = PIN_ITERATIONS;
    payload.pinHash = hashPin(pin, salt, PIN_ITERATIONS);
  }

  private static byte[] hashPin(String pin, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt,
        Math.max(1, iterations), PIN_BITS);
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      return factory.generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("The backup PIN could not be processed.", e);
    } finally {
      spec.clearPassword();
    }
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
      String salt = root.optString("pin_salt", "");
      String hash = root.optString("pin_hash", "");
      if (salt.length() > 0 && hash.length() > 0) {
        payload.pinSalt = Base64.decode(salt, Base64.DEFAULT);
        payload.pinHash = Base64.decode(hash, Base64.DEFAULT);
        payload.pinIterations = root.optInt("pin_iterations", PIN_ITERATIONS);
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
      if (payload.pinSalt != null && payload.pinHash != null) {
        root.put("pin_salt", Base64.encodeToString(payload.pinSalt, Base64.NO_WRAP));
        root.put("pin_hash", Base64.encodeToString(payload.pinHash, Base64.NO_WRAP));
        root.put("pin_iterations", payload.pinIterations);
      }
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
    byte[] pinSalt;
    byte[] pinHash;
    int pinIterations = PIN_ITERATIONS;
  }
}
