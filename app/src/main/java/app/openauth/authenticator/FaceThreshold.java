package app.openauth.authenticator;

/**
 * Decision rules for the on-device face check.
 *
 * <p>The threshold was calibrated with the synthetic face regression test in
 * {@code tests/FaceTemplateVectors.java}; it sits inside the gap that separates matches of
 * the enrolled person from matches of a different person.
 */
public final class FaceThreshold {
  /** Similarity at or above which a capture is treated as the enrolled person. */
  public static final double VALUE = 0.90;
  /** Number of consecutive matching captures required before the app is unlocked. */
  public static final int REQUIRED_MATCHES = 2;

  private FaceThreshold() {}
}
