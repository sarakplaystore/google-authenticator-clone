import app.openauth.authenticator.EnrollAutoCapture;

/**
 * Dependency-free checks for enrolment auto-capture timing. The camera
 * delivers a frame roughly every 110 ms; a pose must be held, brief
 * dropouts (a blink, one unusable frame) must not restart the hold, and
 * a real loss of the pose must.
 *
 * <p>Run after {@code ./build.sh}:</p>
 * <pre>
 * javac -cp build/manual/classes -d build/test-classes tests/EnrollAutoCaptureVectors.java
 * java -cp build/manual/classes:build/test-classes EnrollAutoCaptureVectors
 * </pre>
 */
public final class EnrollAutoCaptureVectors {
  private static final long FRAME_MS = 110L;

  public static void main(String[] args) {
    // A held pose captures automatically after about HOLD_MS, never
    // before, and then re-arms (a rejected sample retries by holding the
    // corrected pose; a successful one advances the step instead).
    EnrollAutoCapture held = new EnrollAutoCapture();
    int captures = 0;
    long capturedAt = -1L;
    for (long t = FRAME_MS; t <= 30 * FRAME_MS; t += FRAME_MS) {
      if (held.onFrame(true, t)) {
        captures++;
        if (capturedAt < 0L) capturedAt = t;
      }
    }
    require(captures >= 1, "a held pose never captured");
    require(capturedAt >= EnrollAutoCapture.HOLD_MS
            && capturedAt <= EnrollAutoCapture.HOLD_MS + 2 * FRAME_MS,
        "capture should first fire after about " + EnrollAutoCapture.HOLD_MS
            + " ms of hold, fired at " + capturedAt);

    // A passing glance does not capture.
    EnrollAutoCapture glance = new EnrollAutoCapture();
    for (long t = FRAME_MS; t <= 3 * FRAME_MS; t += FRAME_MS) {
      require(!glance.onFrame(true, t), "a 3-frame glance captured a sample");
    }
    require(glance.progress(3 * FRAME_MS) > 0f
            && glance.progress(3 * FRAME_MS) < 1f,
        "a partial hold should show partial progress");

    // One dropped frame mid-hold (a blink swallows the eye landmarks)
    // freezes the hold instead of restarting it.
    EnrollAutoCapture dropout = new EnrollAutoCapture();
    long t = 0L;
    boolean captured = false;
    for (int frame = 0; frame < 30 && !captured; frame++) {
      t += FRAME_MS;
      captured = dropout.onFrame(frame != 3, t);
    }
    require(captured, "a single dropped frame restarted the enrolment hold");
    require(t <= EnrollAutoCapture.HOLD_MS + 4 * FRAME_MS,
        "dropout-tolerant capture took too long: " + t);

    // Losing the pose for longer than the grace period starts over:
    // banked progress must be gone, and a fresh full hold is required.
    EnrollAutoCapture lost = new EnrollAutoCapture();
    t = 0L;
    for (int frame = 0; frame < 4; frame++) {
      t += FRAME_MS;
      require(!lost.onFrame(true, t), "an early partial hold captured");
    }
    float banked = lost.progress(t);
    require(banked > 0f, "partial hold banked no progress");
    long lostAt = t;
    while (t - lostAt <= EnrollAutoCapture.GRACE_MS + FRAME_MS) {
      t += FRAME_MS;
      require(!lost.onFrame(false, t), "a lost pose captured a sample");
    }
    require(lost.progress(t) == 0f, "progress survived a loss beyond the grace period");
    int readyFrames = 0;
    captured = false;
    for (int frame = 0; frame < 30 && !captured; frame++) {
      t += FRAME_MS;
      readyFrames++;
      captured = lost.onFrame(true, t);
    }
    require(captured, "a re-held pose never captured after a long loss");
    require(readyFrames * FRAME_MS >= EnrollAutoCapture.HOLD_MS,
        "capture after a long loss needed only " + readyFrames + " ready frames");

    // Reset between enrolment steps drops any banked hold.
    EnrollAutoCapture stepped = new EnrollAutoCapture();
    stepped.onFrame(true, FRAME_MS);
    stepped.onFrame(true, 2 * FRAME_MS);
    stepped.reset();
    require(stepped.progress(2 * FRAME_MS) == 0f, "reset kept banked progress");

    System.out.println("Passed enrolment auto-capture checks: held pose, "
        + "passing glance, single-frame dropout, long loss, step reset.");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
