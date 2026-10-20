package app.openauth.authenticator;

/**
 * Decides when an enrolment pose has been held long enough to capture
 * automatically, so registration never needs a shutter tap.
 *
 * <p>The activity used to count four <em>consecutive</em> pose-ready frames.
 * On a real camera that count kept resetting: a blink drops the eye
 * landmarks for a frame, auto-exposure hunting makes one descriptor
 * unusable, the presence check flickers mid-turn — and the user, holding
 * the pose correctly, was stranded until they found the "Capture now"
 * button. This machine instead integrates <em>time</em>:</p>
 *
 * <ul>
 *   <li>The pose must accumulate {@link #HOLD_MS} of ready time. Camera
 *       frames arrive roughly every 110 ms, so that is a deliberate
 *       half-second hold, not a passing glance.</li>
 *   <li>A dropout shorter than {@link #GRACE_MS} freezes the accumulated
 *       time instead of resetting it; a longer loss starts the hold over.</li>
 *   <li>When the hold completes, {@link #onFrame} returns {@code true}
 *       and the machine resets. A successful capture advances the step
 *       (which also resets the machine), so a step cannot capture twice;
 *       if a sample is rejected as unusable, holding the corrected pose
 *       for another hold period retries automatically.</li>
 * </ul>
 *
 * <p>Pure Java over caller-supplied timestamps so the timing can be unit
 * tested on a plain JVM, in the style of {@link LivenessSession}.</p>
 */
public final class EnrollAutoCapture {
  /** Ready time a pose must accumulate before it is captured. */
  public static final long HOLD_MS = 500L;
  /** A pose loss shorter than this pauses the hold instead of resetting it. */
  public static final long GRACE_MS = 700L;
  /**
   * Largest gap between two ready frames that can count as continuous
   * hold time; a stalled camera cannot bank a whole hold in one jump.
   */
  private static final long MAX_FRAME_GAP_MS = 300L;

  private long accumulatedMs;
  private long lastReadyMs = -1L;
  private long lostSinceMs = -1L;

  /**
   * Feeds one analysed frame. Returns {@code true} on the single frame
   * where the hold completes and the caller should capture the sample.
   */
  public boolean onFrame(boolean poseReady, long tMs) {
    if (!poseReady) {
      if (lastReadyMs >= 0L && lostSinceMs < 0L) lostSinceMs = tMs;
      if (lostSinceMs >= 0L && tMs - lostSinceMs > GRACE_MS) reset();
      return false;
    }
    if (lastReadyMs < 0L) {
      accumulatedMs = 0L;
    } else if (lostSinceMs >= 0L) {
      // Back within the grace period: keep what was banked, but the time
      // spent lost does not count towards the hold.
      if (tMs - lostSinceMs > GRACE_MS) accumulatedMs = 0L;
    } else {
      long delta = tMs - lastReadyMs;
      if (delta > 0L) accumulatedMs += Math.min(delta, MAX_FRAME_GAP_MS);
    }
    lastReadyMs = tMs;
    lostSinceMs = -1L;
    if (accumulatedMs >= HOLD_MS) {
      reset();
      return true;
    }
    return false;
  }

  /** 0..1 hold progress for the on-screen ring, as of {@code tMs}. */
  public float progress(long tMs) {
    if (lastReadyMs < 0L) return 0f;
    if (lostSinceMs >= 0L && tMs - lostSinceMs > GRACE_MS) return 0f;
    return Math.max(0f, Math.min(1f, accumulatedMs / (float) HOLD_MS));
  }

  /** Drops any banked hold, e.g. when a new enrolment step starts. */
  public void reset() {
    accumulatedMs = 0L;
    lastReadyMs = -1L;
    lostSinceMs = -1L;
  }
}
