import app.openauth.authenticator.Account;
import app.openauth.authenticator.Totp;
import java.io.UnsupportedEncodingException;

/** Small, dependency-free RFC 6238 regression test. Run against app/build/manual/classes. */
public final class TotpVectors {
  private static final long[] TIMES = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
  private static final String[][] EXPECTED = {
    {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"},
    {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"},
    {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"}
  };
  private static final String[] ALGORITHMS = {"SHA1", "SHA256", "SHA512"};

  public static void main(String[] args) throws Exception {
    byte[][] keys = {
      ascii("12345678901234567890"),
      ascii("12345678901234567890123456789012"),
      ascii("1234567890123456789012345678901234567890123456789012345678901234")
    };
    for (int algorithm = 0; algorithm < ALGORITHMS.length; algorithm++) {
      Account account = new Account("test", "RFC test", "", base32(keys[algorithm]),
          ALGORITHMS[algorithm], 8, 30);
      for (int vector = 0; vector < TIMES.length; vector++) {
        String actual = Totp.code(account, TIMES[vector] * 1000L);
        String expected = EXPECTED[algorithm][vector];
        if (!expected.equals(actual)) {
          throw new AssertionError(ALGORITHMS[algorithm] + " at " + TIMES[vector]
              + ": expected " + expected + ", got " + actual);
        }
      }
    }

    Account parsed = Account.fromOtpAuthUri(
        "otpauth://totp/Example%3Ajane%40example.com?secret="
            + "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&algorithm=SHA1&digits=6&period=30");
    if (!"jane@example.com".equals(parsed.getName()) || !"Example".equals(parsed.getIssuer())) {
      throw new AssertionError("otpauth URI parsing failed");
    }
    System.out.println("Passed 18 RFC 6238 vectors and an otpauth URI parsing check.");
  }

  private static byte[] ascii(String value) throws UnsupportedEncodingException {
    return value.getBytes("US-ASCII");
  }

  private static String base32(byte[] bytes) {
    final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    StringBuilder result = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        result.append(alphabet[(buffer >> bits) & 31]);
      }
    }
    if (bits > 0) result.append(alphabet[(buffer << (5 - bits)) & 31]);
    return result.toString();
  }
}
