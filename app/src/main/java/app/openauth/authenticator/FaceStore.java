package app.openauth.authenticator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the enrolled face samples, encrypted with the Android Keystore, together with the
 * face unlock preferences. The samples never leave the device.
 */
public final class FaceStore {
  private static final String PREFS = "private_authenticator_face";
  private static final String SAMPLES = "encrypted_face_samples_v1";
  private static final String ENABLED = "face_unlock_enabled";
  private static final String GRACE = "lock_grace_seconds";
  private static final String KEY_ALIAS = "openauthenticator.face.v1";
  private static final int DEFAULT_GRACE_SECONDS = 30;
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private final SharedPreferences preferences;

  public FaceStore(Context context) {
    preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Whether the app should ask for a face before it shows any code. */
  public boolean isEnabled() {
    return preferences.getBoolean(ENABLED, false) && loadSamples() != null;
  }

  /** Turns the face lock on or off, keeping the enrolled samples. */
  public void setEnabled(boolean enabled) {
    preferences.edit().putBoolean(ENABLED, enabled).apply();
  }

  /** Seconds the app may stay in the background before it asks for a face again. */
  public int getGraceSeconds() {
    return preferences.getInt(GRACE, DEFAULT_GRACE_SECONDS);
  }

  /** Sets the background grace period in seconds. */
  public void setGraceSeconds(int seconds) {
    preferences.edit().putInt(GRACE, Math.max(0, seconds)).apply();
  }

  /** The enrolled samples, or null when nothing is enrolled or the data cannot be read. */
  public synchronized float[][] loadSamples() {
    String stored = preferences.getString(SAMPLES, null);
    if (stored == null || stored.length() == 0) return null;
    try {
      byte[] cleartext = decrypt(stored);
      ByteBuffer buffer = ByteBuffer.wrap(cleartext).order(ByteOrder.LITTLE_ENDIAN);
      int count = buffer.getInt();
      if (count <= 0 || count > 32) return null;
      float[][] samples = new float[count][];
      for (int i = 0; i < count; i++) {
        int length = buffer.getInt();
        if (length != FaceTemplate.LENGTH) return null;
        float[] sample = new float[length];
        for (int j = 0; j < length; j++) sample[j] = buffer.getFloat();
        samples[i] = sample;
      }
      return samples;
    } catch (Exception e) {
      return null;
    }
  }

  /** Saves the enrolled samples, encrypted for this device only. */
  public synchronized void saveSamples(float[][] samples) {
    if (samples == null || samples.length == 0) {
      clear();
      return;
    }
    try {
      int size = 4;
      for (float[] sample : samples) size += 4 + sample.length * 4;
      ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(samples.length);
      for (float[] sample : samples) {
        buffer.putInt(sample.length);
        for (float value : sample) buffer.putFloat(value);
      }
      String encrypted = encrypt(buffer.array());
      preferences.edit().putString(SAMPLES, encrypted).apply();
    } catch (Exception e) {
      throw new IllegalStateException("The face data could not be saved securely.", e);
    }
  }

  /** Removes the enrolled samples and turns the face lock off. */
  public void clear() {
    preferences.edit().remove(SAMPLES).putBoolean(ENABLED, false).apply();
  }

  private byte[] decrypt(String value) throws Exception {
    String[] parts = value.split(":", 3);
    if (parts.length != 3 || !"1".equals(parts[0])) {
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
    return "1:" + Base64.encodeToString(iv, Base64.NO_WRAP)
        + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
  }

  private SecretKey getOrCreateKey() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    if (keyStore.containsAlias(KEY_ALIAS)) {
      return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
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
}
