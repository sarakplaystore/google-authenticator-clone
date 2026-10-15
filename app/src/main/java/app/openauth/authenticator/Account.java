package app.openauth.authenticator;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One time-based one-time-password account. Secrets are stored only by {@link AccountStore}. */
public final class Account {
  private final String id;
  private final String name;
  private final String issuer;
  private final String secret;
  private final String algorithm;
  private final int digits;
  private final int period;

  public Account(String id, String name, String issuer, String secret,
      String algorithm, int digits, int period) {
    this.id = id == null || id.length() == 0 ? UUID.randomUUID().toString() : id;
    this.name = name == null ? "" : name.trim();
    this.issuer = issuer == null ? "" : issuer.trim();
    this.secret = normalizeSecret(secret);
    this.algorithm = normalizeAlgorithm(algorithm);
    this.digits = digits;
    this.period = period;
    if (this.name.length() == 0) {
      throw new IllegalArgumentException("Enter an account name.");
    }
    if (digits != 6 && digits != 8) {
      throw new IllegalArgumentException("Only 6- or 8-digit codes are supported.");
    }
    if (period < 1 || period > 300) {
      throw new IllegalArgumentException("The code period must be between 1 and 300 seconds.");
    }
    // Validate alphabet and padding before the secret is persisted.
    Totp.decodeBase32(this.secret);
  }

  public static Account fromManual(String name, String issuer, String secret) {
    return new Account(null, name, issuer, secret, "SHA1", 6, 30);
  }

  /** Parses a standard otpauth://totp/ URI (the format used by authenticator QR codes). */
  public static Account fromOtpAuthUri(String value) {
    if (value == null || value.trim().length() == 0) {
      throw new IllegalArgumentException("The QR code is empty.");
    }
    try {
      URI uri = new URI(value.trim());
      if (!"otpauth".equalsIgnoreCase(uri.getScheme())) {
        throw new IllegalArgumentException("This is not an authenticator QR code.");
      }
      if (uri.getHost() == null || !"totp".equalsIgnoreCase(uri.getHost())) {
        throw new IllegalArgumentException("Only time-based (TOTP) accounts are supported.");
      }

      Map<String, String> query = parseQuery(uri.getRawQuery());
      String secret = query.get("secret");
      if (secret == null || secret.trim().length() == 0) {
        throw new IllegalArgumentException("The QR code does not contain a setup key.");
      }

      String label = decode(uri.getRawPath() == null ? "" : uri.getRawPath());
      if (label.startsWith("/")) {
        label = label.substring(1);
      }
      String pathIssuer = "";
      int colon = label.indexOf(':');
      if (colon >= 0) {
        pathIssuer = label.substring(0, colon).trim();
        label = label.substring(colon + 1).trim();
      }
      String issuer = query.containsKey("issuer") ? query.get("issuer") : pathIssuer;
      if (label.length() == 0) {
        label = issuer == null || issuer.trim().length() == 0 ? "Account" : issuer.trim();
      }

      String algorithm = query.containsKey("algorithm") ? query.get("algorithm") : "SHA1";
      int digits = parseInt(query.get("digits"), 6, "digits");
      int period = parseInt(query.get("period"), 30, "period");
      return new Account(null, label, issuer, secret, algorithm, digits, period);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("The QR code could not be read.", e);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<String, String>();
    if (rawQuery == null || rawQuery.length() == 0) {
      return values;
    }
    String[] pairs = rawQuery.split("&");
    for (String pair : pairs) {
      int equals = pair.indexOf('=');
      String key = decode(equals < 0 ? pair : pair.substring(0, equals));
      String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
      if (!values.containsKey(key.toLowerCase(Locale.US))) {
        values.put(key.toLowerCase(Locale.US), value);
      }
    }
    return values;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static int parseInt(String value, int fallback, String field) {
    if (value == null || value.length() == 0) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("The QR code has an invalid " + field + " value.");
    }
  }

  private static String normalizeAlgorithm(String value) {
    String normalized = value == null ? "SHA1" : value.replace("-", "").toUpperCase(Locale.US);
    if ("SHA1".equals(normalized) || "SHA256".equals(normalized) || "SHA512".equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("The QR code uses an unsupported algorithm.");
  }

  static String normalizeSecret(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Enter the setup key.");
    }
    StringBuilder normalized = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = Character.toUpperCase(value.charAt(i));
      if (Character.isWhitespace(ch) || ch == '-') {
        continue;
      }
      normalized.append(ch);
    }
    while (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) == '=') {
      normalized.setLength(normalized.length() - 1);
    }
    if (normalized.length() == 0) {
      throw new IllegalArgumentException("Enter the setup key.");
    }
    return normalized.toString();
  }

  public Account withName(String replacement) {
    return new Account(id, replacement, issuer, secret, algorithm, digits, period);
  }

  public String getId() { return id; }
  public String getName() { return name; }
  public String getIssuer() { return issuer; }
  public String getSecret() { return secret; }
  public String getAlgorithm() { return algorithm; }
  public int getDigits() { return digits; }
  public int getPeriod() { return period; }

  public String getIssuerOrDefault() {
    return issuer.length() == 0 ? "Authenticator" : issuer;
  }
}
