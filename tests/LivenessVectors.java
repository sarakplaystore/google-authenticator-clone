import app.openauth.authenticator.FaceSignals;
import app.openauth.authenticator.FaceTemplate;
import app.openauth.authenticator.LivenessSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Dependency-free checks for the anti-spoofing face check. A synthetic face
 * is rendered frame by frame into NV21 buffers; a driver reads the
 * session's randomised prompts and "performs" them the way a live person
 * would (blink, turn with 3D-style parallax, nod, face brightening when
 * the screen flashes). Coaching scenarios check the live guidance
 * (hold, eyes closed, wrong direction), a wrong-way recovery, and a
 * missed prompt that is repeated once. Attack scenarios replay the same
 * harness with a static photo, a rigidly moved photo (no parallax, no
 * blink), a moiré-covered screen replay that ignores the flash, a live
 * impostor, and a live owner whose face never responds to the screen light.
 *
 * <p>Run after {@code ./build.sh}:</p>
 * <pre>
 * javac -cp build/manual/classes -d build/test-classes tests/LivenessVectors.java
 * java -cp build/manual/classes:build/test-classes LivenessVectors
 * </pre>
 */
public final class LivenessVectors {
  private static final int W = 480;
  private static final int H = 640;
  private static final long FRAME_MS = 110L;

  public static void main(String[] args) {
    signalChecks();

    HashSet<String> orders = new HashSet<String>();
    HashSet<LivenessSession.Guidance> guidanceSeen =
        new HashSet<LivenessSession.Guidance>();
    for (int seed = 1; seed <= 5; seed++) {
      Result live = runScenario(new Scenario(seed));
      require(live.session.isPassed(),
          "live owner (seed " + seed + ") did not pass: " + live.session.getPhase()
              + "/" + live.session.getFailReason() + " after " + live.frames + " frames, seq="
              + live.sequence);
      require(live.sequence.size() == 3,
          "expected 3 challenges, got " + live.sequence);
      require(new HashSet<LivenessSession.Challenge>(live.sequence).size() == 3,
          "challenges must be distinct: " + live.sequence);
      orders.add(live.sequence.toString());
      guidanceSeen.addAll(live.guidanceSeen);
      System.out.println("live seed " + seed + " passed in " + live.frames
          + " frames, challenges " + live.sequence);
    }
    require(orders.size() > 1, "challenge order must vary between attempts: " + orders);
    require(guidanceSeen.contains(LivenessSession.Guidance.HOLD),
        "a held turn/nod was never reported as HOLD: " + guidanceSeen);
    require(guidanceSeen.contains(LivenessSession.Guidance.EYES_CLOSED),
        "closed eyes during a blink were never reported: " + guidanceSeen);

    // A user who starts turning the wrong way must get a wrong-direction
    // hint, and must still be able to recover and pass.
    Scenario mirrored = new Scenario(6);
    mirrored.mirrorFirstTurn = true;
    Result mirroredResult = runScenario(mirrored);
    require(mirroredResult.guidanceSeen.contains(LivenessSession.Guidance.WRONG_DIRECTION),
        "turning the opposite way never produced a hint: "
            + mirroredResult.guidanceSeen);
    require(mirroredResult.session.isPassed(),
        "a short wrong-way detour should still recover: "
            + mirroredResult.session.getPhase() + "/"
            + mirroredResult.session.getFailReason());
    System.out.println("wrong-way prompt recovered: " + mirroredResult.sequence);

    // A user who misses the first prompt entirely should get it repeated
    // with the retry coaching instead of losing the whole attempt.
    Scenario delayed = new Scenario(7);
    delayed.delayFirstChallengeMs = 9400L;
    Result delayedResult = runScenario(delayed);
    require(delayedResult.session.getRetriesUsed() >= 1,
        "a missed prompt was not retried");
    require(delayedResult.session.isPassed(),
        "a retried prompt did not let the live owner pass: "
            + delayedResult.session.getPhase() + "/"
            + delayedResult.session.getFailReason()
            + " retries=" + delayedResult.session.getRetriesUsed()
            + " done=" + delayedResult.session.getChallengesDone()
            + " frames=" + delayedResult.frames
            + " seq=" + delayedResult.sequence
            + " seen=" + delayedResult.guidanceSeen);
    require(delayedResult.guidanceSeen.contains(
            LivenessSession.Guidance.RETRY_CHALLENGE),
        "the retry notice was never shown: " + delayedResult.guidanceSeen);
    System.out.println("missed prompt retried once, then passed; retries="
        + delayedResult.session.getRetriesUsed());

    Scenario photo = new Scenario(3);
    photo.staticPhoto = true;
    Result photoResult = runScenario(photo);
    require(!photoResult.session.isPassed(), "a static photo passed the live check");
    require(photoResult.session.getChallengesDone() == 0,
        "a static photo completed a challenge: " + photoResult.sequence);
    require(photoResult.session.getPhase() == LivenessSession.Phase.FAILED
            && photoResult.session.getFailReason() == LivenessSession.FailReason.TIMEOUT,
        "a static photo should end as a timeout, got " + photoResult.session.getPhase()
            + "/" + photoResult.session.getFailReason());
    System.out.println("static photo rejected after retries: "
        + photoResult.session.getFailReason());

    Scenario rigid = new Scenario(4);
    rigid.rigidPhoto = true;
    Result rigidResult = runScenario(rigid);
    require(!rigidResult.session.isPassed(),
        "a rigidly moved photo passed the live check (seq=" + rigidResult.sequence + ")");
    require(rigidResult.session.getPhase() == LivenessSession.Phase.FAILED
            && rigidResult.session.getFailReason() == LivenessSession.FailReason.TIMEOUT,
        "a rigid photo should end as a timeout, got " + rigidResult.session.getPhase()
            + "/" + rigidResult.session.getFailReason());
    System.out.println("rigid photo rejected: " + rigidResult.session.getFailReason());

    Scenario screen = new Scenario(2);
    screen.moire = true;
    screen.flashResponsive = false;
    Result screenResult = runScenario(screen);
    require(!screenResult.session.isPassed(), "a screen replay passed the live check");
    require(screenResult.session.getFailReason()
            == LivenessSession.FailReason.SPOOF_SCREEN,
        "screen replay should fail as SPOOF_SCREEN, got "
            + screenResult.session.getFailReason());
    System.out.println("screen replay rejected: SPOOF_SCREEN");

    Scenario impostor = new Scenario(5);
    impostor.variant = 2;
    Result impostorResult = runScenario(impostor);
    require(!impostorResult.session.isPassed(),
        "a live impostor performing the challenges passed (seq="
            + impostorResult.sequence + ")");
    System.out.println("live impostor rejected: " + impostorResult.session.getFailReason());

    Scenario dark = new Scenario(1);
    dark.flashResponsive = false;
    Result darkResult = runScenario(dark);
    require(!darkResult.session.isPassed(),
        "a face that never responds to the screen flash passed");
    require(darkResult.session.getFailReason()
            == LivenessSession.FailReason.NO_LIGHT_RESPONSE,
        "expected NO_LIGHT_RESPONSE, got " + darkResult.session.getFailReason());
    System.out.println("no light response rejected: NO_LIGHT_RESPONSE");

    System.out.println("Passed liveness checks: live owner (5 random orders), "
        + "coaching (hold, wrong way, retry), static photo, rigid photo, "
        + "screen replay, impostor, flash response.");
  }

  // ------------------------------------------------------------- signal units

  private static void signalChecks() {
    FaceSignals open = signals(new Pose());
    require(open.eyesFound && open.mouthFound, "straight face landmarks not found");
    require(open.geometryLooksLikeFace(), "straight face failed geometry check");
    require(open.moireScore < 0.15f, "clean face moire too high: " + open.moireScore);

    // Same face, detector-framed crop (3.2x eye distance, centred just below
    // the eye pair). All landmark windows must still lock onto the face.
    FaceSignals guided = FaceSignals.fromFrame(synth(new Pose()), W, H, 0,
        0.5f, 0.4f, 0.613333f);
    require(guided != null, "guided-crop signals were null");
    require(guided.eyesFound && guided.mouthFound,
        "guided-crop landmarks not found");
    require(guided.geometryLooksLikeFace(), "guided crop failed geometry check");

    Pose closed = new Pose();
    closed.closed = 1f;
    FaceSignals shut = signals(closed);
    require(shut.eyesFound, "closed-eye frame lost the eyes");
    require(shut.eyeOpenness < 0.62f * open.eyeOpenness,
        "blink openness dip too small: open=" + open.eyeOpenness
            + " closed=" + shut.eyeOpenness);

    Pose left = new Pose();
    left.yaw = 1f;
    FaceSignals turnedLeft = signals(left);
    require(turnedLeft.eyesFound && turnedLeft.mouthFound, "turned face landmarks lost");
    require(turnedLeft.eyeMidX - open.eyeMidX > 0.045f,
        "user-left turn should move the eye midpoint towards image-right: "
            + (turnedLeft.eyeMidX - open.eyeMidX));
    float parallax = (turnedLeft.mouthX - turnedLeft.eyeMidX)
        - (open.mouthX - open.eyeMidX);
    require(parallax > 0.02f, "real turn should show mouth/eye parallax: " + parallax);

    Pose rigid = new Pose();
    rigid.yaw = 1f;
    rigid.rigid = true;
    FaceSignals rigidTurn = signals(rigid);
    require(rigidTurn.eyesFound && rigidTurn.mouthFound, "rigid photo landmarks lost");
    float rigidParallax = (rigidTurn.mouthX - rigidTurn.eyeMidX)
        - (open.mouthX - open.eyeMidX);
    require(rigidParallax < 0.0105f,
        "rigid photo must not show turn parallax: " + rigidParallax);

    Pose nod = new Pose();
    nod.pitch = 1f;
    FaceSignals nodded = signals(nod);
    require(nodded.eyeMidY - open.eyeMidY > 0.05f,
        "nod should move the eyes down: " + (nodded.eyeMidY - open.eyeMidY));

    Pose screen = new Pose();
    screen.moire = true;
    FaceSignals replay = signals(screen);
    require(replay.moireScore >= 0.42f,
        "moire pattern not detected: " + replay.moireScore);
    System.out.println("signals ok: openness " + open.eyeOpenness + "->" + shut.eyeOpenness
        + ", parallax " + parallax + " (rigid " + rigidParallax + "), moire clean "
        + open.moireScore + " vs screen " + replay.moireScore);
  }

  private static FaceSignals signals(Pose pose) {
    FaceSignals signals = FaceSignals.fromFrame(synth(pose), W, H, 0);
    require(signals != null, "signals were null");
    return signals;
  }

  // ----------------------------------------------------------- scenario driver

  private static final class Scenario {
    final long seed;
    int variant;
    boolean staticPhoto;
    boolean rigidPhoto;
    boolean moire;
    boolean flashResponsive = true;
    /** Deliberately turn the wrong way for the first turn prompt. */
    boolean mirrorFirstTurn;
    /** Ignore the first prompt for this long, to exercise the retry. */
    long delayFirstChallengeMs;

    Scenario(long seed) {
      this.seed = seed;
    }
  }

  private static final class Result {
    LivenessSession session;
    int frames;
    final List<LivenessSession.Challenge> sequence =
        new ArrayList<LivenessSession.Challenge>();
    final HashSet<LivenessSession.Guidance> guidanceSeen =
        new HashSet<LivenessSession.Guidance>();
  }

  private static Result runScenario(Scenario scenario) {
    FaceTemplate enrStraight = FaceTemplate.fromFrame(synth(new Pose()), W, H, 0);
    Pose poseLeft = new Pose();
    poseLeft.yaw = 0.8f;
    Pose poseRight = new Pose();
    poseRight.yaw = -0.8f;
    FaceTemplate enrLeft = FaceTemplate.fromFrame(synth(poseLeft), W, H, 0);
    FaceTemplate enrRight = FaceTemplate.fromFrame(synth(poseRight), W, H, 0);
    boolean posesDistinct = enrLeft.similarity(enrRight) <= 0.965f;

    Result result = new Result();
    LivenessSession session =
        new LivenessSession(new Random(scenario.seed), posesDistinct, 0L);
    result.session = session;

    Pose pose = new Pose();
    pose.variant = scenario.variant;
    pose.rigid = scenario.rigidPhoto;
    pose.moire = scenario.moire;

    LivenessSession.Challenge lastChallenge = null;
    int challengeFrames = 0;
    int retriesSeen = 0;
    long firstChallengeAtMs = -1L;
    long t = 0L;
    for (int frame = 0; frame < 600; frame++) {
      t += FRAME_MS;
      result.frames = frame + 1;

      LivenessSession.Challenge challenge = session.getCurrentChallenge();
      if (challenge != lastChallenge) {
        lastChallenge = challenge;
        challengeFrames = 0;
        if (challenge != null) {
          result.sequence.add(challenge);
          if (firstChallengeAtMs < 0L) firstChallengeAtMs = t;
        }
      } else {
        challengeFrames++;
      }
      // A repeated prompt starts a fresh attempt: restart the local gesture
      // timing so a delayed blink is performed again after the retry.
      if (session.getRetriesUsed() != retriesSeen) {
        retriesSeen = session.getRetriesUsed();
        challengeFrames = 0;
      }

      boolean ignoreFirstPrompt = scenario.delayFirstChallengeMs > 0L
          && result.sequence.size() == 1
          && t - firstChallengeAtMs < scenario.delayFirstChallengeMs;
      if (!scenario.staticPhoto && !ignoreFirstPrompt) {
        if (scenario.mirrorFirstTurn && result.sequence.size() == 1
            && challengeFrames < 5 && isTurn(challenge)) {
          // Pull in the opposite direction of the first turn prompt.
          drive(pose, session.getPhase(), opposite(challenge), challengeFrames);
        } else {
          drive(pose, session.getPhase(), challenge, challengeFrames);
        }
      }
      pose.seed = 1000L + frame;
      pose.bright = scenario.flashResponsive && session.isFlashOn(t) ? 18 : 0;

      byte[] pixels = synth(pose);
      FaceTemplate template = FaceTemplate.fromFrame(pixels, W, H, 0);
      FaceSignals signals = FaceSignals.fromFrame(pixels, W, H, 0);
      LivenessSession.Frame input = new LivenessSession.Frame();
      input.tMs = t;
      input.signals = signals;
      input.usable = template != null && template.isUsable();
      input.facePresent = signals != null && signals.geometryLooksLikeFace();
      input.flashOn = session.isFlashOn(t);
      if (template != null) {
        input.simStraight = template.similarity(enrStraight);
        input.simLeft = template.similarity(enrLeft);
        input.simRight = template.similarity(enrRight);
        input.strictStraight = template.matchesStrict(enrStraight);
      }
      session.onFrame(input);
      if (session.isFinished()) break;
      result.guidanceSeen.add(session.getGuidance());
    }
    return result;
  }

  private static boolean isTurn(LivenessSession.Challenge challenge) {
    return challenge == LivenessSession.Challenge.TURN_LEFT
        || challenge == LivenessSession.Challenge.TURN_RIGHT;
  }

  private static LivenessSession.Challenge opposite(LivenessSession.Challenge challenge) {
    return challenge == LivenessSession.Challenge.TURN_LEFT
        ? LivenessSession.Challenge.TURN_RIGHT
        : LivenessSession.Challenge.TURN_LEFT;
  }

  /** Moves the synthetic pose towards whatever the session asks for. */
  private static void drive(Pose pose, LivenessSession.Phase phase,
      LivenessSession.Challenge challenge, int challengeFrames) {
    float targetYaw = 0f;
    float targetPitch = 0f;
    float targetClosed = 0f;
    if (phase == LivenessSession.Phase.CHALLENGE && challenge != null) {
      switch (challenge) {
        case TURN_LEFT:
          targetYaw = 1f;
          break;
        case TURN_RIGHT:
          targetYaw = -1f;
          break;
        case NOD_DOWN:
          targetPitch = 1f;
          break;
        case BLINK:
          // Open for a beat, close over two frames, hold, reopen.
          if (challengeFrames >= 3 && challengeFrames <= 6) targetClosed = 1f;
          break;
        default:
          break;
      }
    }
    pose.yaw = approach(pose.yaw, targetYaw, 0.18f);
    pose.pitch = approach(pose.pitch, targetPitch, 0.18f);
    pose.closed = approach(pose.closed, pose.rigid ? 0f : targetClosed, 0.55f);
  }

  private static float approach(float value, float target, float step) {
    if (value < target) return Math.min(target, value + step);
    if (value > target) return Math.max(target, value - step);
    return value;
  }

  // --------------------------------------------------------- synthetic face

  private static final class Pose {
    int variant;
    float yaw;     // +1 = turned to the user's left (features move image-right)
    float pitch;   // 0..1 nod down
    float closed;  // 0 open .. 1 shut
    boolean rigid; // flat-photo warp instead of per-feature 3D motion
    boolean moire;
    int bright;
    long seed = 7L;
  }

  private static byte[] synth(Pose p) {
    byte[] pixels = new byte[W * H * 3 / 2];
    Random random = new Random(p.seed);
    float rx = 118, ry = 158;
    int eyeGap0 = 46, eyeYOff = -55, eyeR = 17;
    int browGap = 24, browH = 7;
    int noseW = 7, noseLen = 62;
    int mouthW = 38, mouthH = 13, mouthYOff = 74;
    int hairCut = (int) (ry * 0.62f);
    int skinBase = 160;
    if (p.variant == 2) {
      rx = 142; ry = 150; eyeGap0 = 67; eyeYOff = -62; eyeR = 13;
      browGap = 33; browH = 11; noseW = 11; noseLen = 50;
      mouthW = 54; mouthH = 10; mouthYOff = 54; hairCut = 82; skinBase = 175;
    }
    float cx = W / 2f, cy = H * 0.44f;

    float scaleX = 1f, scaleY = 1f, shiftX = 0f, shiftY = 0f;
    float eyeShiftX = 0f, noseShiftX = 0f, mouthShiftX = 0f;
    float headShiftX = 0f, headShiftY = 0f, eyeDropY = 0f, mouthDropY = 0f;
    float eyeGap = eyeGap0;
    if (p.rigid) {
      // A plane rotated in front of the camera: one warp for every feature.
      scaleX = 1f - 0.22f * Math.abs(p.yaw);
      shiftX = p.yaw * 18f;
      scaleY = 1f - 0.15f * p.pitch;
      shiftY = p.pitch * 14f;
    } else {
      // A 3D head: the protruding nose/mouth travel further than the eyes.
      headShiftX = p.yaw * 6f;
      headShiftY = p.pitch * 8f;
      eyeShiftX = p.yaw * 17f;
      noseShiftX = p.yaw * 30f;
      mouthShiftX = p.yaw * 32f;
      eyeGap = eyeGap0 * (1f - 0.20f * Math.abs(p.yaw));
      eyeDropY = p.pitch * 22f;
      mouthDropY = p.pitch * 11f;
    }
    float fcx = cx + headShiftX, fcy = cy + headShiftY;
    float eyeY = fcy + eyeYOff + eyeDropY;
    float eyeLx = fcx + eyeShiftX - eyeGap, eyeRx = fcx + eyeShiftX + eyeGap;
    float mouthCx = fcx + mouthShiftX, mouthCy = fcy + mouthYOff + mouthDropY;
    float eyeRy = Math.max(2.2f, eyeR * (1f - 0.85f * p.closed));
    float eyeValue = 35f + 25f * p.closed;

    for (int py = 0; py < H; py++) {
      for (int px = 0; px < W; px++) {
        float sx = px, sy = py;
        if (p.rigid) {
          sx = cx + (px - cx - shiftX) / scaleX;
          sy = cy + (py - cy - shiftY) / scaleY;
        }
        float nx = (sx - fcx) / rx;
        float ny = (sy - fcy) / ry;
        float r2 = nx * nx + ny * ny;
        float value = 48f + py * 0.05f + (((px / 40 + py / 40) % 2 == 0) ? 18f : 0f);
        if (r2 < 1f) {
          value = skinBase - 25f * r2;
          if (sy < fcy - hairCut && r2 < 0.98f) value = 62f;
          if (ellipse(sx, sy, eyeLx, eyeY, eyeR, eyeRy) < 1f
              || ellipse(sx, sy, eyeRx, eyeY, eyeR, eyeRy) < 1f) {
            value = eyeValue;
          }
          if (sy > eyeY - browH - 13 && sy < eyeY - 13
              && Math.abs(sx - eyeLx) < browGap) value = 50f;
          if (sy > eyeY - browH - 13 && sy < eyeY - 13
              && Math.abs(sx - eyeRx) < browGap) value = 50f;
          if (Math.abs(sx - (fcx + noseShiftX)) < noseW
              && sy > eyeY && sy < eyeY + noseLen) value -= 28f;
          if (ellipse(sx, sy, mouthCx, mouthCy, mouthW, mouthH) < 1f) value = 88f;
          value += p.rigid ? 0f : p.yaw * (sx - fcx) * 0.05f;
        }
        if (p.moire) {
          value += 13f * (float) (Math.sin(px * 2 * Math.PI / 13.0)
              * Math.sin(py * 2 * Math.PI / 11.0));
          value += 10f * (float) Math.sin((px * 0.8 + py) * 2 * Math.PI / 29.0);
        }
        value += p.bright + (random.nextFloat() - 0.5f) * 6f;
        pixels[py * W + px] = (byte) (int) Math.max(0, Math.min(255, value));
      }
    }
    return pixels;
  }

  private static float ellipse(float x, float y, float cx, float cy, float rx, float ry) {
    float dx = (x - cx) / rx, dy = (y - cy) / ry;
    return dx * dx + dy * dy;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
