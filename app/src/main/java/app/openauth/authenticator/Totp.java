package app.openauth.authenticator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 4226 / RFC 6238 HOTP and TOTP primitives. */
public final class Totp {
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  public static String code(Account account, long timeMillis) {
    if (account == null) {
      throw new IllegalArgumentException("An account is required.");
    }
    byte[] key = decodeBase32(account.getSecret());
    long counter = timeMillis / (account.getPeriod() * 1000L);
    byte[] message = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array();
    try {
      Mac mac = Mac.getInstance("Hmac" + account.getAlgorithm());
      mac.init(new SecretKeySpec(key, "Hmac" + account.getAlgorithm()));
      byte[] hash = mac.doFinal(message);
      int offset = hash[hash.length - 1] & 0x0f;
      long binary = ((hash[offset] & 0x7fL) << 24)
          | ((hash[offset + 1] & 0xffL) << 16)
          | ((hash[offset + 2] & 0xffL) << 8)
          | (hash[offset + 3] & 0xffL);
      long modulus = 1L;
      for (int i = 0; i < account.getDigits(); i++) {
        modulus *= 10L;
      }
      return String.format(Locale.US, account.getDigits() == 6 ? "%06d" : "%08d", binary % modulus);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("This device cannot generate the selected code type.", e);
    }
  }

  /** Returns the number of milliseconds until this account's next code. */
  public static long remainingMillis(Account account, long timeMillis) {
    long periodMillis = account.getPeriod() * 1000L;
    long elapsed = timeMillis % periodMillis;
    return periodMillis - elapsed;
  }

  /** Decodes RFC 4648 Base32, accepting optional spaces, dashes and trailing padding. */
  public static byte[] decodeBase32(String value) {
    String secret = Account.normalizeSecret(value);
    ByteArrayOutputStream output = new ByteArrayOutputStream(secret.length() * 5 / 8);
    int buffer = 0;
    int bitsLeft = 0;
    for (int i = 0; i < secret.length(); i++) {
      char ch = secret.charAt(i);
      int digit = BASE32.indexOf(ch);
      if (digit < 0) {
        throw new IllegalArgumentException("The setup key contains an invalid character.");
      }
      buffer = (buffer << 5) | digit;
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        bitsLeft -= 8;
        output.write((buffer >> bitsLeft) & 0xff);
      }
    }
    int remainder = secret.length() % 8;
    if (remainder == 1 || remainder == 3 || remainder == 6) {
      throw new IllegalArgumentException("The setup key has an invalid Base32 length.");
    }
    if (bitsLeft > 0 && (buffer & ((1 << bitsLeft) - 1)) != 0) {
      throw new IllegalArgumentException("The setup key has invalid Base32 padding bits.");
    }
    if (output.size() == 0) {
      throw new IllegalArgumentException("The setup key is too short.");
    }
    return output.toByteArray();
  }
}
