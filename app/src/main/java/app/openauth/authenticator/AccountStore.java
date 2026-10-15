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

/** Stores authenticator accounts as AES-GCM ciphertext protected by the Android Keystore. */
public final class AccountStore {
  private static final String PREFS = "private_authenticator_data";
  private static final String DATA = "encrypted_accounts_v1";
  private static final String KEY_ALIAS = "openauthenticator.aes.v1";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private final SharedPreferences preferences;

  public AccountStore(Context context) {
    preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public synchronized List<Account> load() {
    String stored = preferences.getString(DATA, null);
    if (stored == null || stored.length() == 0) {
      return new ArrayList<Account>();
    }
    try {
      byte[] cleartext = decrypt(stored);
      JSONArray array = new JSONArray(new String(cleartext, UTF8));
      List<Account> accounts = new ArrayList<Account>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.getJSONObject(i);
        accounts.add(new Account(
            item.getString("id"), item.getString("name"), item.optString("issuer", ""),
            item.getString("secret"), item.optString("algorithm", "SHA1"),
            item.optInt("digits", 6), item.optInt("period", 30)));
      }
      return accounts;
    } catch (Exception e) {
      throw new IllegalStateException("Saved codes could not be decrypted on this device.", e);
    }
  }

  public synchronized void save(List<Account> accounts) {
    try {
      JSONArray array = new JSONArray();
      for (Account account : accounts) {
        JSONObject item = new JSONObject();
        item.put("id", account.getId());
        item.put("name", account.getName());
        item.put("issuer", account.getIssuer());
        item.put("secret", account.getSecret());
        item.put("algorithm", account.getAlgorithm());
        item.put("digits", account.getDigits());
        item.put("period", account.getPeriod());
        array.put(item);
      }
      String encrypted = encrypt(array.toString().getBytes(UTF8));
      if (!preferences.edit().putString(DATA, encrypted).commit()) {
        throw new IllegalStateException("The account list could not be saved.");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("The account list could not be saved securely.", e);
    }
  }

  private byte[] decrypt(String value) throws Exception {
    String[] parts = value.split(":", 3);
    if (parts.length != 3 || !"1".equals(parts[0])) {
      throw new IllegalArgumentException("Unknown encrypted account format.");
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
