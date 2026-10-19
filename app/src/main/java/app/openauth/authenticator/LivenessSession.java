package app.openauth.authenticator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One face-unlock attempt: identity matching fused with randomised
 * challenge–response liveness.
 *
 * <p>A photograph of the owner matches the identity descriptor perfectly, so
 * identity alone can never be the whole check. A session therefore requires,
 * in order:</p>
 *
 * <ol>
 *   <li>a centred, straight-on baseline of the enrolled face;</li>
 *   <li>three live challenges drawn at random (blink, turn left, turn right,
 *       nod down) in a random order, returning to centre between them. Every
 *       frame that counts towards a challenge must still match the enrolled
 *       identity (for turns, the enrolled pose template captured at that
 *       angle), so an attacker cannot show the owner's photo for the match
 *       and their own live face for the movements. Turns additionally check
 *       mouth-vs-eye parallax, which a flat photo moved in front of the
 *       camera does not exhibit;</li>
 *   <li>a final strict identity confirmation (both descriptor components
 *       must agree) on three net centred frames — one stray frame delays the
 *       confirmation instead of restarting it;</li>
 *   <li>throughout, the app flashes the screen at random times and the face
 *       must measurably brighten in response (skipped in already-bright
 *       surroundings where the screen cannot add light), and no persistent
 *       screen-moiré pattern may be present. Extra pulses are scheduled only
 *       if a slow attempt has not gathered enough lit/unlit evidence yet.</li>
 * </ol>
 *
 * <p>The class is a pure state machine over timestamped {@link Frame} inputs:
 * the camera, platform face detector and UI live in the activity, which makes
 * the whole policy unit-testable on a plain JVM with synthetic frame
 * sequences (tests/LivenessVectors).</p>
 *
 * <p>Prompts are meant to be followed, not guessed at. The session reports
 * {@link Guidance} for the current frame (turn further, wrong way, hold,
 * eyes still closed, retry) and the partial progress of a hold, so the screen
 * can coach the user instead of silently waiting for a timeout. A prompt that
 * is not completed in time is repeated once with the retry message before the
 * attempt fails; an attacker still has to perform every challenge, and the
 * overall session timeout still bounds the attempt.</p>
 */
public final class LivenessSession {
  public enum Challenge { BLINK, TURN_LEFT, TURN_RIGHT, NOD_DOWN }

  public enum Phase {
    BASELINE, CHALLENGE, RETURN_CENTER, FINAL_CONFIRM, PASSED, FAILED
  }

  public enum FailReason {
    NONE, TIMEOUT, WRONG_FACE, SPOOF_SCREEN, NO_LIGHT_RESPONSE, MULTI_FACE,
    FACE_LOST
  }

  /** Per-frame coaching hint for the current prompt. */
  public enum Guidance {
    /** Nothing to add to the prompt. */
    NONE,
    /** The head is moving the right way but has not reached the pose yet. */
    TURN_MORE,
    /** The head moved towards the opposite side of the prompt. */
    WRONG_DIRECTION,
    /** The pose is reached; hold it until the ring fills. */
    HOLD,
    /** Eyes are closed during the blink prompt; open them now. */
    EYES_CLOSED,
    /** The prompt timed out and is being repeated. */
    RETRY_CHALLENGE
  }

  /** One analysed camera frame, filled in by the activity. */
  public static final class Frame {
    public long tMs;
    /** Liveness signals for this frame; may be null on a bad buffer. */
    public FaceSignals signals;
    /** True when the descriptor was computable and usable quality. */
    public boolean usable;
    /** True when exactly one plausible face is present in the guide area. */
    public boolean facePresent;
    /** True when a second face was detected. */
    public boolean multiFace;
    /** True when the screen flash was lit for this frame. */
    public boolean flashOn;
    /** Identity similarities against straight / left / right enrolments. */
    public float simStraight = -1f;
    public float simLeft = -1f;
    public float simRight = -1f;
    /** Strict component-wise match against the straight enrolment. */
    public boolean strictStraight;
  }

  // Identity gates. The "floor" gates frames that may count towards a
  // challenge; only the final strict confirmation can pass the session, so a
  // near-miss impostor gains nothing by performing the movements.
  static final float IDENTITY_FLOOR = 0.70f;
  static final float POSE_FLOOR = 0.62f;
  static final float POSE_MARGIN = 0.025f;
  static final float WRONG_FACE_SCORE = 0.60f;

  // Geometry deltas in crop-relative units (1.0 = whole crop width).
  static final float CENTER_TOLERANCE = 0.021f;
  static final float TURN_DELTA = 0.032f;
  /** Head travel that is visible but below TURN_DELTA -> "a little more". */
  static final float TURN_HINT = 0.011f;
  static final float TURN_PARALLAX = 0.0105f;
  static final float NOD_DELTA = 0.030f;
  static final float NOD_HINT = 0.010f;
  static final float NOD_COMPRESSION = 0.006f;
  static final float BLINK_DIP = 0.58f;
  static final float BLINK_RECOVER = 0.78f;
  static final float MIN_BASELINE_OPENNESS = 0.16f;

  static final float MOIRE_HIT = 0.42f;
  static final int MOIRE_HITS_TO_FAIL = 7;

  static final int BASELINE_FRAMES = 8;
  static final int HOLD_FRAMES = 3;
  static final int FINAL_STRICT_FRAMES = 3;
  static final int CHALLENGES_REQUIRED = 3;
  /** One repeat per prompt: a missed prompt is coaching, not an attack. */
  static final int CHALLENGE_RETRIES = 1;

  private static final long BASELINE_TIMEOUT_MS = 8000L;
  private static final long CHALLENGE_TIMEOUT_MS = 9000L;
  private static final long RETURN_TIMEOUT_MS = 6000L;
  private static final long FINAL_TIMEOUT_MS = 7000L;
  // Long enough for three prompts plus a repeat of each. The per-prompt
  // timers, not this ceiling, are what actually bound the attempt.
  private static final long SESSION_TIMEOUT_MS = 55000L;
  private static final long FACE_LOST_MS = 7000L;
  private static final long BLINK_RECOVER_MS = 1700L;
  private static final long RETRY_NOTICE_MS = 2400L;
  private static final long FLASH_EDGE_MS = 170L;
  private static final float FLASH_MIN_RESPONSE = 3.0f;
  private static final float FLASH_BRIGHT_AMBIENT = 175f;
  /** Cap on re-armed pulses so a stalled attempt cannot flash forever. */
  private static final int MAX_FLASH_PULSES = 12;

  private final Random random;
  private final boolean poseTemplatesDistinct;
  private final long startedAtMs;
  private final List<long[]> flashPulses = new ArrayList<long[]>();

  private Phase phase = Phase.BASELINE;
  private FailReason failReason = FailReason.NONE;
  private List<Challenge> challenges = new ArrayList<Challenge>();
  private int challengeIndex;
  private long phaseStartedAtMs;
  private boolean timersStarted;

  // Baseline accumulation (straight-on, eyes open).
  private int baselineGoodFrames;
  private int baselineEyeFrames;
  private int baselineMouthFrames;
  private float sumEyeX;
  private float sumEyeY;
  private float sumMouthRelX;
  private float sumMouthRelY;
  private float sumOpenness;
  private float baseEyeX;
  private float baseEyeY;
  private float baseMouthRelX;
  private float baseMouthRelY;
  private float baseOpenness;

  private int holdFrames;
  private int strictFrames;
  private long blinkDipAtMs = -1L;
  private int wrongFaceFrames;
  private int multiFaceFrames;
  private int moireHits;
  private long lastPresentAtMs = -1L;
  private long lastFrameAtMs;

  // Coaching state for the screen. Written on the frame thread (the UI
  // thread), read right after onFrame returns.
  private Guidance guidance = Guidance.NONE;
  private int[] challengeRetries = new int[0];
  private int retriesUsed;
  private long retryNoticeUntilMs = -1L;

  private final List<Float> flashOnLuma = new ArrayList<Float>();
  private final List<Float> flashOffLuma = new ArrayList<Float>();
  private final Set<Integer> litPulsesSampled = new HashSet<Integer>();

  /**
   * @param random source of challenge order and flash timing; use a
   *     {@link java.security.SecureRandom} in production so the sequence is
   *     unpredictable, a seeded {@link Random} in tests.
   * @param poseTemplatesDistinct true when the enrolled left/right samples
   *     clearly differ from each other and can vouch for turn direction.
   *     Legacy enrolments that fail this fall back to geometry + parallax.
   */
  public LivenessSession(Random random, boolean poseTemplatesDistinct, long startedAtMs) {
    this.random = random;
    this.poseTemplatesDistinct = poseTemplatesDistinct;
    this.startedAtMs = startedAtMs;
    this.phaseStartedAtMs = startedAtMs;
    // Three screen flashes at unpredictable times inside the first seconds:
    // long enough to collect lit/unlit comparisons while the user is still
    // in the early, centred phases.
    long cursor = startedAtMs + 800L + nextLong(700L);
    for (int i = 0; i < 3; i++) {
      long duration = 650L + nextLong(350L);
      flashPulses.add(new long[] {cursor, cursor + duration});
      cursor += duration + 700L + nextLong(900L);
    }
  }

  private long nextLong(long bound) {
    return (long) (random.nextDouble() * bound);
  }

  /** True while the activity should light the screen flash. */
  public boolean isFlashOn(long tMs) {
    for (long[] pulse : flashPulses) {
      if (tMs >= pulse[0] && tMs < pulse[1]) return true;
    }
    maybeExtendFlashes(tMs);
    for (long[] pulse : flashPulses) {
      if (tMs >= pulse[0] && tMs < pulse[1]) return true;
    }
    return false;
  }

  /**
   * The three opening flashes normally provide all the evidence the light
   * check needs. A slow attempt — for example a user who asked for a prompt
   * to be repeated — can outlast them, and then the final confirmation used
   * to wait for flashes that would never come and time out. Schedule another
   * pair instead, but only while frames from this phase can actually be
   * sampled, and only up to {@link #MAX_FLASH_PULSES} in total.
   */
  private void maybeExtendFlashes(long tMs) {
    if (flashPulses.size() >= MAX_FLASH_PULSES) return;
    if (phase == Phase.PASSED || phase == Phase.FAILED) return;
    // Turn and nod frames are deliberately not sampled (the head angle
    // changes the lighting), so adding flashes then would only waste them.
    if (phase == Phase.CHALLENGE && getCurrentChallenge() != Challenge.BLINK) return;
    long lastEnd = flashPulses.get(flashPulses.size() - 1)[1];
    if (tMs < lastEnd + 500L) return;
    if (flashReady(tMs)) return;
    long cursor = tMs + 450L + nextLong(550L);
    for (int i = 0; i < 2 && flashPulses.size() < MAX_FLASH_PULSES; i++) {
      long duration = 650L + nextLong(350L);
      flashPulses.add(new long[] {cursor, cursor + duration});
      cursor += duration + 900L + nextLong(900L);
    }
  }

  public Phase getPhase() {
    return phase;
  }

  public FailReason getFailReason() {
    return failReason;
  }

  public boolean isPassed() {
    return phase == Phase.PASSED;
  }

  public boolean isFinished() {
    return phase == Phase.PASSED || phase == Phase.FAILED;
  }

  /** The challenge the user should perform now, or null outside challenges. */
  public Challenge getCurrentChallenge() {
    if (phase != Phase.CHALLENGE || challengeIndex >= challenges.size()) return null;
    return challenges.get(challengeIndex);
  }

  public int getChallengesDone() {
    if (phase == Phase.PASSED) return challenges.size();
    return Math.min(challengeIndex, challenges.size());
  }

  public int getChallengesTotal() {
    return challenges.isEmpty() ? CHALLENGES_REQUIRED : challenges.size();
  }

  /** Overall completion 0..1 for the progress ring. */
  public float getProgress() {
    if (phase == Phase.PASSED) return 1f;
    int total = getChallengesTotal();
    float baselinePart = phase == Phase.BASELINE
        ? baselineGoodFrames / (float) BASELINE_FRAMES : 1f;
    float weighted = 0.18f * baselinePart;
    if (phase == Phase.CHALLENGE || phase == Phase.RETURN_CENTER
        || phase == Phase.FINAL_CONFIRM || phase == Phase.FAILED) {
      float current = phase == Phase.CHALLENGE
          ? holdFrames / (float) HOLD_FRAMES * 0.6f : 0f;
      weighted += 0.64f * (getChallengesDone() + current) / total;
    }
    if (phase == Phase.FINAL_CONFIRM) {
      weighted += 0.18f * Math.min(strictFrames, FINAL_STRICT_FRAMES)
          / (float) FINAL_STRICT_FRAMES;
    }
    return Math.max(0f, Math.min(1f, weighted));
  }

  /** Coaching hint for the prompt on screen; never null. */
  public Guidance getGuidance() {
    return guidance;
  }

  /** 0..1 progress of the current "hold this pose" prompt. */
  public float getChallengeProgress() {
    if (phase != Phase.CHALLENGE) return 0f;
    return Math.max(0f, Math.min(1f, holdFrames / (float) HOLD_FRAMES));
  }

  /** How many frames of the current hold have been accepted (0..3). */
  public int getHoldFrames() {
    return phase == Phase.CHALLENGE ? holdFrames : 0;
  }

  /** Number of prompts repeated so far in this attempt. */
  public int getRetriesUsed() {
    return retriesUsed;
  }

  /** True while the "let's try that again" notice should be shown. */
  public boolean isRetryNotice(long nowMs) {
    return retryNoticeUntilMs >= 0 && nowMs < retryNoticeUntilMs;
  }

  /** Feeds one analysed frame. Frames must arrive in timestamp order. */
  public void onFrame(Frame frame) {
    if (isFinished() || frame == null) return;
    long now = frame.tMs;
    lastFrameAtMs = now;
    if (!timersStarted) {
      timersStarted = true;
      phaseStartedAtMs = now;
      lastPresentAtMs = now;
    }
    if (now - startedAtMs > SESSION_TIMEOUT_MS) {
      fail(FailReason.TIMEOUT);
      return;
    }

    boolean present = frame.usable && frame.facePresent && frame.signals != null;
    if (present) lastPresentAtMs = now;
    if (now - lastPresentAtMs > FACE_LOST_MS && baselineGoodFrames > 0) {
      fail(FailReason.FACE_LOST);
      return;
    }

    if (frame.multiFace) {
      if (++multiFaceFrames >= 3) {
        fail(FailReason.MULTI_FACE);
        return;
      }
    } else {
      multiFaceFrames = 0;
    }

    if (frame.signals != null) {
      if (frame.signals.moireScore >= MOIRE_HIT) {
        if (++moireHits >= MOIRE_HITS_TO_FAIL) {
          fail(FailReason.SPOOF_SCREEN);
          return;
        }
      } else if (frame.signals.moireScore < 0.30f && moireHits > 0) {
        moireHits--;
      }
    }

    collectFlashSample(frame, present);

    // A face that is continuously present but clearly not the enrolled one
    // ends the attempt early instead of letting an impostor rehearse.
    if (present && frame.simStraight >= 0f && frame.simStraight < WRONG_FACE_SCORE) {
      if (++wrongFaceFrames >= 12) {
        fail(FailReason.WRONG_FACE);
        return;
      }
    } else if (present) {
      wrongFaceFrames = 0;
    }

    switch (phase) {
      case BASELINE:
        onBaselineFrame(frame, present, now);
        break;
      case CHALLENGE:
        onChallengeFrame(frame, present, now);
        break;
      case RETURN_CENTER:
        onReturnFrame(frame, present, now);
        break;
      case FINAL_CONFIRM:
        onFinalFrame(frame, present, now);
        break;
      default:
        break;
    }
  }

  private void onBaselineFrame(Frame frame, boolean present, long now) {
    guidance = Guidance.NONE;
    if (baselineGoodFrames > 0 && now - phaseStartedAtMs > BASELINE_TIMEOUT_MS) {
      fail(FailReason.TIMEOUT);
      return;
    }
    if (!present) return;
    FaceSignals signals = frame.signals;
    if (!signals.eyesFound) return;
    if (Math.abs(signals.eyeMidX - 0.5f) > 0.14f) return;
    if (frame.simStraight < IDENTITY_FLOOR) return;
    if (baselineGoodFrames == 0) phaseStartedAtMs = now;

    baselineGoodFrames++;
    baselineEyeFrames++;
    if (signals.mouthFound) baselineMouthFrames++;
    sumEyeX += signals.eyeMidX;
    sumEyeY += signals.eyeMidY;
    sumOpenness += signals.eyeOpenness;
    if (signals.mouthFound) {
      sumMouthRelX += signals.mouthX - signals.eyeMidX;
      sumMouthRelY += signals.mouthY - signals.eyeMidY;
    }
    if (baselineGoodFrames < BASELINE_FRAMES) return;

    baseEyeX = sumEyeX / baselineGoodFrames;
    baseEyeY = sumEyeY / baselineGoodFrames;
    baseOpenness = sumOpenness / baselineGoodFrames;
    int mouthDenominator = Math.max(1, baselineMouthFrames);
    baseMouthRelX = sumMouthRelX / mouthDenominator;
    baseMouthRelY = sumMouthRelY / mouthDenominator;
    challenges = buildChallenges();
    challengeRetries = new int[challenges.size()];
    for (int i = 0; i < challengeRetries.length; i++) {
      challengeRetries[i] = CHALLENGE_RETRIES;
    }
    challengeIndex = 0;
    holdFrames = 0;
    blinkDipAtMs = -1L;
    guidance = Guidance.NONE;
    phase = Phase.CHALLENGE;
    phaseStartedAtMs = now;
  }

  private List<Challenge> buildChallenges() {
    List<Challenge> pool = new ArrayList<Challenge>();
    if (baseOpenness >= MIN_BASELINE_OPENNESS
        && baselineEyeFrames >= BASELINE_FRAMES - 1) {
      pool.add(Challenge.BLINK);
    }
    pool.add(Challenge.TURN_LEFT);
    pool.add(Challenge.TURN_RIGHT);
    if (baselineMouthFrames >= BASELINE_FRAMES - 2) {
      pool.add(Challenge.NOD_DOWN);
    }
    Collections.shuffle(pool, random);
    int required = Math.min(CHALLENGES_REQUIRED, pool.size());
    return new ArrayList<Challenge>(pool.subList(0, required));
  }

  private void onChallengeFrame(Frame frame, boolean present, long now) {
    if (retryNoticeUntilMs >= 0 && now >= retryNoticeUntilMs) {
      retryNoticeUntilMs = -1L;
    }
    if (now - phaseStartedAtMs > CHALLENGE_TIMEOUT_MS) {
      // A prompt that nobody answered is almost always a user who did not
      // see or understand it, not an attacker. Repeat it once, with the
      // screen saying so, before the attempt is counted as failed.
      if (retryChallenge(now)) return;
      fail(FailReason.TIMEOUT);
      return;
    }
    if (!present) {
      holdFrames = 0;
      guidance = Guidance.NONE;
      return;
    }
    Challenge challenge = getCurrentChallenge();
    if (challenge == null) return;
    boolean satisfied;
    switch (challenge) {
      case BLINK:
        satisfied = blinkSatisfied(frame, now);
        break;
      case TURN_LEFT:
        satisfied = turnSatisfied(frame, true);
        break;
      case TURN_RIGHT:
        satisfied = turnSatisfied(frame, false);
        break;
      case NOD_DOWN:
      default:
        satisfied = nodSatisfied(frame);
        break;
    }
    if (challenge == Challenge.BLINK) {
      // A blink is an event, not a pose to hold; blinkSatisfied only
      // returns true once on the recovery frame.
      if (satisfied) {
        completeChallenge(now);
      } else {
        guidance = blinkDipAtMs >= 0 ? Guidance.EYES_CLOSED : Guidance.NONE;
      }
      return;
    }
    if (satisfied) {
      if (++holdFrames >= HOLD_FRAMES) {
        completeChallenge(now);
        return;
      }
      // Partial credit is visible to the user: the ring fills while the
      // pose is held, so a momentary wobble no longer looks like nothing
      // happened.
      guidance = Guidance.HOLD;
    } else {
      holdFrames = 0;
      guidance = directionGuidance(challenge, frame);
    }
  }

  private boolean retryChallenge(long now) {
    if (challengeIndex < 0 || challengeIndex >= challengeRetries.length) return false;
    if (challengeRetries[challengeIndex] <= 0) return false;
    challengeRetries[challengeIndex]--;
    retriesUsed++;
    holdFrames = 0;
    blinkDipAtMs = -1L;
    retryNoticeUntilMs = now + RETRY_NOTICE_MS;
    phaseStartedAtMs = now;
    guidance = Guidance.RETRY_CHALLENGE;
    return true;
  }

  /**
   * Distinguishes "you are doing it, go a little further" from "you turned
   * the other way". Both cases used to look identical on screen — the app
   * said "turn left" and then silently rejected a user who turned right.
   */
  private Guidance directionGuidance(Challenge challenge, Frame frame) {
    if (retryNoticeUntilMs >= 0 && lastFrameAtMs < retryNoticeUntilMs) {
      return Guidance.RETRY_CHALLENGE;
    }
    FaceSignals signals = frame.signals;
    if (signals == null || !signals.eyesFound) return Guidance.NONE;
    if (challenge == Challenge.TURN_LEFT || challenge == Challenge.TURN_RIGHT) {
      float yaw = signals.eyeMidX - baseEyeX;
      boolean toUserLeft = challenge == Challenge.TURN_LEFT;
      if (toUserLeft ? yaw <= -TURN_HINT : yaw >= TURN_HINT) {
        return Guidance.WRONG_DIRECTION;
      }
      if (toUserLeft ? yaw >= TURN_HINT : yaw <= -TURN_HINT) {
        return Guidance.TURN_MORE;
      }
      return Guidance.NONE;
    }
    if (challenge == Challenge.NOD_DOWN) {
      float pitch = signals.eyeMidY - baseEyeY;
      if (pitch <= -NOD_HINT) return Guidance.WRONG_DIRECTION;
      if (pitch >= NOD_HINT) return Guidance.TURN_MORE;
    }
    return Guidance.NONE;
  }

  private boolean blinkSatisfied(Frame frame, long now) {
    FaceSignals signals = frame.signals;
    if (!signals.eyesFound || frame.simStraight < IDENTITY_FLOOR) return false;
    // Two complementary blink cues: the eye-box contrast collapses, and/or
    // the detected eye point jumps up to the brow (the strongest remaining
    // dark blob once the lid closes) while the mouth stays put.
    boolean dipped = signals.eyeOpenness < BLINK_DIP * baseOpenness
        || (baseEyeY - signals.eyeMidY > 0.026f
            && signals.mouthFound
            && Math.abs((signals.mouthY - signals.eyeMidY) - baseMouthRelY) > 0.02f
            && Math.abs(signals.mouthY - (baseEyeY + baseMouthRelY)) < 0.03f);
    if (blinkDipAtMs < 0) {
      if (dipped) blinkDipAtMs = now;
      return false;
    }
    if (now - blinkDipAtMs > BLINK_RECOVER_MS) {
      blinkDipAtMs = -1L;
      return false;
    }
    return signals.eyeOpenness > BLINK_RECOVER * baseOpenness
        && Math.abs(signals.eyeMidY - baseEyeY) < 0.02f;
  }

  /**
   * Turn check fusing three evidences: the eye midpoint must travel in the
   * challenged direction, the mouth must travel further than the eyes
   * (3D parallax a flat print does not have), and — when the enrolment's
   * pose samples are distinct — the frame must match the enrolled template
   * for that side better than the opposite side.
   *
   * <p>Direction convention: frames here are in unmirrored sensor
   * coordinates, where the user's own left appears on the right of the
   * image. Turning to their left therefore moves features towards larger x.</p>
   */
  private boolean turnSatisfied(Frame frame, boolean toUserLeft) {
    FaceSignals signals = frame.signals;
    if (!signals.eyesFound || !signals.mouthFound) return false;
    float yaw = signals.eyeMidX - baseEyeX;
    float parallax = (signals.mouthX - signals.eyeMidX) - baseMouthRelX;
    float pose = toUserLeft ? frame.simLeft : frame.simRight;
    float opposite = toUserLeft ? frame.simRight : frame.simLeft;
    if (poseTemplatesDistinct) {
      if (pose < POSE_FLOOR || pose - opposite < POSE_MARGIN) return false;
    } else if (frame.simStraight < IDENTITY_FLOOR - 0.08f) {
      return false;
    }
    return toUserLeft
        ? yaw >= TURN_DELTA && parallax >= TURN_PARALLAX
        : yaw <= -TURN_DELTA && parallax <= -TURN_PARALLAX;
  }

  private boolean nodSatisfied(Frame frame) {
    FaceSignals signals = frame.signals;
    if (!signals.eyesFound || !signals.mouthFound) return false;
    if (frame.simStraight < IDENTITY_FLOOR - 0.08f) return false;
    float pitch = signals.eyeMidY - baseEyeY;
    float compression = baseMouthRelY - (signals.mouthY - signals.eyeMidY);
    return pitch >= NOD_DELTA && compression >= NOD_COMPRESSION;
  }

  private void completeChallenge(long now) {
    holdFrames = 0;
    blinkDipAtMs = -1L;
    guidance = Guidance.NONE;
    retryNoticeUntilMs = -1L;
    phase = Phase.RETURN_CENTER;
    phaseStartedAtMs = now;
  }

  private void onReturnFrame(Frame frame, boolean present, long now) {
    guidance = Guidance.NONE;
    if (now - phaseStartedAtMs > RETURN_TIMEOUT_MS) {
      fail(FailReason.TIMEOUT);
      return;
    }
    if (!present || !frame.signals.eyesFound) {
      holdFrames = 0;
      return;
    }
    if (frame.simStraight < IDENTITY_FLOOR) {
      holdFrames = 0;
      return;
    }
    float yaw = Math.abs(frame.signals.eyeMidX - baseEyeX);
    float pitch = Math.abs(frame.signals.eyeMidY - baseEyeY);
    if (yaw <= CENTER_TOLERANCE && pitch <= CENTER_TOLERANCE) {
      if (++holdFrames >= 2) {
        holdFrames = 0;
        challengeIndex++;
        phaseStartedAtMs = now;
        phase = challengeIndex >= challenges.size()
            ? Phase.FINAL_CONFIRM : Phase.CHALLENGE;
      }
    } else {
      holdFrames = 0;
    }
  }

  private void onFinalFrame(Frame frame, boolean present, long now) {
    guidance = Guidance.NONE;
    if (now - phaseStartedAtMs > FINAL_TIMEOUT_MS) {
      // Reaching the final step and never confirming strictly means the
      // face performing the movements was not the enrolled one.
      fail(strictFrames == 0 ? FailReason.WRONG_FACE : FailReason.TIMEOUT);
      return;
    }
    if (!present || !frame.signals.eyesFound) {
      if (strictFrames > 0) strictFrames--;
      return;
    }
    float yaw = Math.abs(frame.signals.eyeMidX - baseEyeX);
    float pitch = Math.abs(frame.signals.eyeMidY - baseEyeY);
    if (frame.strictStraight && yaw <= CENTER_TOLERANCE && pitch <= CENTER_TOLERANCE) {
      if (++strictFrames >= FINAL_STRICT_FRAMES && flashReady(now)) {
        passOrFlashFail();
      }
    } else if (strictFrames > 0) {
      // Decay instead of reset: one stray frame (a blink, a focus hiccup)
      // used to throw away two good confirmations and force the whole
      // three-frame hold to start over.
      strictFrames--;
    }
  }

  private void collectFlashSample(Frame frame, boolean present) {
    if (!present) return;
    // During turn/nod challenges the head angle changes how the face is
    // lit, which would contaminate the comparison. The blink challenge is
    // performed facing the camera, so its frames are usable.
    if (phase == Phase.CHALLENGE && getCurrentChallenge() != Challenge.BLINK) return;
    long t = frame.tMs;
    for (int i = 0; i < flashPulses.size(); i++) {
      long[] pulse = flashPulses.get(i);
      if (Math.abs(t - pulse[0]) < FLASH_EDGE_MS
          || Math.abs(t - pulse[1]) < FLASH_EDGE_MS) {
        return; // exposure is still settling around an edge
      }
      if (frame.flashOn && t >= pulse[0] && t < pulse[1]) {
        litPulsesSampled.add(i);
      }
    }
    List<Float> bucket = frame.flashOn ? flashOnLuma : flashOffLuma;
    if (bucket.size() < 80) bucket.add(frame.signals.meanLuma);
  }

  /**
   * Whether enough lit/unlit evidence exists to judge the flash check, or
   * the surroundings are so bright that the screen cannot add light anyway.
   * Evidence must come from at least two separate random flashes, so one
   * lucky frame cannot stand in for the light response.
   */
  private boolean flashReady(long now) {
    if (flashOffLuma.size() >= 5 && median(flashOffLuma) >= FLASH_BRIGHT_AMBIENT) {
      return true;
    }
    if (flashPulses.isEmpty()) return true;
    return litPulsesSampled.size() >= 2
        && flashOnLuma.size() >= 3
        && flashOffLuma.size() >= 4;
  }

  private void passOrFlashFail() {
    if (flashOffLuma.size() >= 4 && flashOnLuma.size() >= 3
        && litPulsesSampled.size() >= 2) {
      float off = median(flashOffLuma);
      float on = median(flashOnLuma);
      if (off < FLASH_BRIGHT_AMBIENT && on - off < FLASH_MIN_RESPONSE) {
        fail(FailReason.NO_LIGHT_RESPONSE);
        return;
      }
    }
    phase = Phase.PASSED;
  }

  private void fail(FailReason reason) {
    phase = Phase.FAILED;
    failReason = reason;
  }

  private static float median(List<Float> values) {
    List<Float> sorted = new ArrayList<Float>(values);
    Collections.sort(sorted);
    int mid = sorted.size() / 2;
    if (sorted.size() % 2 == 1) return sorted.get(mid);
    return (sorted.get(mid - 1) + sorted.get(mid)) / 2f;
  }
}
