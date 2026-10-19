import app.openauth.authenticator.FaceSignals;
import app.openauth.authenticator.FaceTemplate;

/**
 * Dependency-free checks for the custom face descriptor. The images are
 * synthetic, so this verifies geometry, lighting normalisation, serialisation,
 * and that a deliberately different face falls below the match threshold.
 *
 * <p>Run after {@code ./build.sh}:</p>
 * <pre>
 * javac -cp build/manual/classes -d build/test-classes tests/FaceTemplateVectors.java
 * java -cp build/manual/classes:build/test-classes FaceTemplateVectors
 * </pre>
 */
public final class FaceTemplateVectors {
  private static final int WIDTH = 480;
  private static final int HEIGHT = 640;

  public static void main(String[] args) {
    byte[] baseFrame = face(0, 1.0f, 0, 0, 0, 0);
    FaceTemplate base = FaceTemplate.fromFrame(baseFrame, WIDTH, HEIGHT, 0);
    require(base != null, "base descriptor was null");
    require(base.isUsable(), "base descriptor was not usable: " + base.getQuality());

    checkSame("brightness up", base,
        FaceTemplate.fromFrame(face(20, 1.0f, 0, 0, 0, 0), WIDTH, HEIGHT, 0));
    checkSame("brightness down", base,
        FaceTemplate.fromFrame(face(-20, 1.0f, 0, 0, 0, 0), WIDTH, HEIGHT, 0));
    checkSame("contrast", base,
        FaceTemplate.fromFrame(face(0, 1.15f, 0, 0, 0, 0), WIDTH, HEIGHT, 0));
    checkSame("small shift", base,
        FaceTemplate.fromFrame(face(0, 1.0f, 10, 6, 0, 0), WIDTH, HEIGHT, 0));
    checkSame("pose left", base,
        FaceTemplate.fromFrame(face(0, 1.0f, 0, 0, -1, 0), WIDTH, HEIGHT, 0));
    checkSame("pose right", base,
        FaceTemplate.fromFrame(face(0, 1.0f, 0, 0, 1, 0), WIDTH, HEIGHT, 0));
    checkSame("smile", base,
        FaceTemplate.fromFrame(face(0, 1.0f, 0, 0, 0, 9), WIDTH, HEIGHT, 0));

    // The activity can lock a detector-framed crop instead of the old fixed
    // centre crop. The synthetic face has an eye pair at (240, 226.6) with a
    // 92 px interpupillary distance; 3.2x that is a 294 px crop centred just
    // below the eyes. It must stay consistent under lighting and motion.
    FaceTemplate guidedBase = FaceTemplate.fromFrame(baseFrame, WIDTH, HEIGHT, 0,
        0.5f, 0.4f, 0.613333f);
    require(guidedBase != null && guidedBase.isUsable(),
        "guided crop descriptor was not usable");
    checkSame("guided brightness", guidedBase,
        FaceTemplate.fromFrame(face(18, 1.0f, 0, 0, 0, 0), WIDTH, HEIGHT, 0,
            0.5f, 0.4f, 0.613333f));
    FaceTemplate guidedShifted = FaceTemplate.fromFrame(face(0, 1.0f, 30, 20, 0, 0),
        WIDTH, HEIGHT, 0, 0.5625f, 0.43125f, 0.613333f);
    require(guidedShifted != null, "guided shifted face descriptor was null");
    checkSame("guided shift", guidedBase, guidedShifted);
    byte[] guidedRotatedSource = rotateForSensor(face(0, 1.0f, 0, 0, 0, 0),
        WIDTH, HEIGHT, 90);
    FaceTemplate guidedRotated = FaceTemplate.fromFrame(guidedRotatedSource, HEIGHT, WIDTH,
        90, 0.5f, 0.4f, 0.613333f);
    require(guidedRotated != null, "guided rotated descriptor was null");
    require(guidedBase.similarity(guidedRotated) >= 0.97f,
        "guided rotation changed the descriptor too much: "
            + guidedBase.similarity(guidedRotated));

    // The activity re-anchors the crop on the measured eye pair before
    // matching. The same face must stay above the threshold after a shift or
    // a small pose change, and the aligned shift must not be worse than the
    // old fixed crop.
    FaceTemplate eyeBase = eyeAligned(baseFrame);
    FaceTemplate eyeShifted = eyeAligned(face(0, 1.0f, 30, 20, 0, 0));
    checkSame("eye-aligned shift", eyeBase, eyeShifted);
    checkSame("eye-aligned brightness", eyeBase,
        eyeAligned(face(20, 1.0f, 0, 0, 0, 0)));
    FaceTemplate fixedShifted =
        FaceTemplate.fromFrame(face(0, 1.0f, 30, 20, 0, 0), WIDTH, HEIGHT, 0);
    float fixedShiftScore = base.similarity(fixedShifted);
    float alignedShiftScore = eyeBase.similarity(eyeShifted);
    require(alignedShiftScore >= fixedShiftScore - 0.01f,
        "eye alignment made a shifted face worse: fixed=" + fixedShiftScore
            + " aligned=" + alignedShiftScore);
    FaceTemplate eyeLeft = eyeAligned(face(0, 1.0f, 0, 0, -1, 0));
    FaceTemplate eyeRight = eyeAligned(face(0, 1.0f, 0, 0, 1, 0));
    checkSame("eye-aligned pose left", eyeBase, eyeLeft);
    checkSame("eye-aligned pose right", eyeBase, eyeRight);
    require(eyeBase.similarity(eyeLeft) > 0.97f,
        "eye-aligned left pose too far from straight: "
            + eyeBase.similarity(eyeLeft));
    float poseMargin = eyeLeft.similarity(eyeLeft) - eyeLeft.similarity(eyeRight);
    require(poseMargin >= 0.025f,
        "eye-aligned pose margin too small for the turn check: " + poseMargin);
    System.out.println("eye alignment: shift " + fixedShiftScore + " -> "
        + alignedShiftScore + ", pose L/R " + eyeLeft.similarity(eyeRight));

    for (int variant = 1; variant <= 6; variant++) {
      byte[] otherFrame = face(0, 1.0f, 0, 0, 0, variant);
      FaceTemplate other = FaceTemplate.fromFrame(otherFrame, WIDTH, HEIGHT, 0);
      require(other != null, "different face " + variant + " was null");
      float score = base.similarity(other);
      require(score < FaceTemplate.MATCH_THRESHOLD,
          "different face " + variant + " scored too high: " + score);
      FaceSignals otherSignals = FaceSignals.fromFrame(otherFrame, WIDTH, HEIGHT, 0);
      if (otherSignals != null && otherSignals.eyesFound) {
        FaceTemplate eyeOther = eyeAligned(otherFrame);
        float alignedScore = eyeBase.similarity(eyeOther);
        require(alignedScore < FaceTemplate.MATCH_THRESHOLD,
            "eye-aligned different face " + variant + " scored too high: "
                + alignedScore);
      }
    }

    byte[] encoded = base.toBytes();
    FaceTemplate decoded = FaceTemplate.fromBytes(encoded);
    require(decoded != null, "descriptor could not be decoded");
    require(base.similarity(decoded) > 0.999f, "serialised descriptor changed");

    byte[] rotatedSource = rotateForSensor(baseFrame, WIDTH, HEIGHT, 90);
    FaceTemplate rotated = FaceTemplate.fromFrame(
        rotatedSource, HEIGHT, WIDTH, 90);
    require(rotated != null, "rotated frame descriptor was null");
    require(base.similarity(rotated) >= 0.97f,
        "rotation changed the descriptor too much: " + base.similarity(rotated));

    // Night: the same face at a fraction of the light must stay usable and
    // still match its well-lit enrolment. Quality is structural contrast
    // relative to the scene, so a dark room no longer pushes the descriptor
    // below the gate and strands the scan on "Move to better light".
    byte[] nightFrame = dim(face(0, 1.0f, 0, 0, 0, 0), 0.45f);
    FaceTemplate night = FaceTemplate.fromFrame(nightFrame, WIDTH, HEIGHT, 0);
    require(night != null, "night descriptor was null");
    require(night.isUsable(), "night descriptor was not usable: " + night.getQuality());
    checkSame("night", base, night);
    byte[] deepNightFrame = dim(face(0, 1.0f, 0, 0, 0, 0), 0.28f);
    FaceTemplate deepNight = FaceTemplate.fromFrame(deepNightFrame, WIDTH, HEIGHT, 0);
    require(deepNight != null, "deep-night descriptor was null");
    require(deepNight.isUsable(),
        "deep-night descriptor was not usable: " + deepNight.getQuality());
    checkSame("deep night", base, deepNight);
    FaceSignals nightSignals = FaceSignals.fromFrame(deepNightFrame, WIDTH, HEIGHT, 0);
    require(nightSignals != null && nightSignals.eyesFound && nightSignals.mouthFound,
        "deep-night landmarks not found");
    for (int variant = 1; variant <= 6; variant++) {
      FaceTemplate nightOther = FaceTemplate.fromFrame(
          dim(face(0, 1.0f, 0, 0, 0, variant), 0.28f), WIDTH, HEIGHT, 0);
      require(nightOther != null, "night impostor " + variant + " was null");
      float score = base.similarity(nightOther);
      require(score < FaceTemplate.MATCH_THRESHOLD,
          "night impostor " + variant + " scored too high: " + score);
    }

    byte[] dark = new byte[WIDTH * HEIGHT * 3 / 2];
    for (int i = 0; i < WIDTH * HEIGHT; i++) dark[i] = 10;
    FaceTemplate darkTemplate = FaceTemplate.fromFrame(dark, WIDTH, HEIGHT, 0);
    require(darkTemplate != null, "dark frame was rejected before quality check");
    require(!darkTemplate.isUsable(), "a featureless dark frame was treated as usable");
    require(FaceTemplate.fromFrame(new byte[12], 10, 10, 0) == null,
        "an invalid frame was accepted");

    System.out.println("Passed custom face descriptor checks: lighting, pose, shift, "
        + "serialisation, rotation, quality, and impostor separation.");
  }

  /**
   * Matches the activity's identity pipeline: locate the eyes in the default
   * crop, re-anchor a crop on them, and describe that crop.
   */
  private static FaceTemplate eyeAligned(byte[] frame) {
    FaceSignals signals = FaceSignals.fromFrame(frame, WIDTH, HEIGHT, 0);
    require(signals != null && signals.eyesFound, "eye landmarks not found for alignment");
    FaceTemplate.FaceCrop crop = FaceTemplate.cropFromEyes(
        signals.eyeMidX, signals.eyeMidY, signals.eyeGap,
        0.5f, 0.36f, 0.72f, WIDTH, HEIGHT);
    FaceTemplate template = FaceTemplate.fromFrame(frame, WIDTH, HEIGHT, 0,
        crop.centerX, crop.centerY, crop.side);
    require(template != null, "eye-aligned descriptor was null");
    require(template.isUsable(), "eye-aligned descriptor was not usable");
    return template;
  }

  private static void checkSame(String label, FaceTemplate base, FaceTemplate candidate) {
    require(candidate != null, label + " descriptor was null");
    float score = base.similarity(candidate);
    require(score >= FaceTemplate.MATCH_THRESHOLD,
        label + " scored below the match threshold: " + score);
  }

  /**
   * Converts an upright grayscale frame into the raw sensor frame that, after
   * rotating clockwise by {@code rotation}, produces the upright image.
   */
  private static byte[] rotateForSensor(byte[] upright, int width, int height, int rotation) {
    boolean swap = rotation == 90 || rotation == 270;
    int sourceWidth = swap ? height : width;
    int sourceHeight = swap ? width : height;
    byte[] result = new byte[sourceWidth * sourceHeight * 3 / 2];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int sourceX;
        int sourceY;
        if (rotation == 90) {
          sourceX = y;
          sourceY = width - 1 - x;
        } else if (rotation == 180) {
          sourceX = width - 1 - x;
          sourceY = height - 1 - y;
        } else if (rotation == 270) {
          sourceX = sourceWidth - 1 - y;
          sourceY = x;
        } else {
          sourceX = x;
          sourceY = y;
        }
        result[sourceY * sourceWidth + sourceX] = upright[y * width + x];
      }
    }
    return result;
  }

  private static byte[] face(int brightness, float contrast, int dx, int dy,
      int pose, int variant) {
    byte[] pixels = new byte[WIDTH * HEIGHT * 3 / 2];
    java.util.Random random = new java.util.Random(1 + variant);
    float cx = WIDTH / 2f + dx + (variant == 4 ? 12 : 0);
    float cy = HEIGHT * 0.44f + dy + (variant == 4 ? -8 : 0);
    float rx = 118, ry = 158;
    int eyeGap = 46, eyeYOff = -55, eyeR = 17;
    int browGap = 24, browH = 7;
    int noseW = 7, noseLen = 62, noseShift = 0;
    int mouthW = 38, mouthH = 13, mouthY = 74, mouthShift = 0;
    int hairCut = (int) (ry * 0.62f);
    int skinBase = 160, background = 0;
    if (variant == 1) {
      rx = 97; ry = 165; eyeGap = 33; eyeYOff = -48; eyeR = 14;
      browGap = 17; browH = 6; noseW = 5; noseLen = 72;
      mouthW = 27; mouthH = 10; mouthY = 82; hairCut = 122;
      skinBase = 145; background = 1;
    } else if (variant == 2) {
      rx = 142; ry = 150; eyeGap = 67; eyeYOff = -62; eyeR = 13;
      browGap = 33; browH = 11; noseW = 11; noseLen = 50;
      mouthW = 54; mouthH = 10; mouthY = 54; hairCut = 82;
      skinBase = 175; background = 2;
    } else if (variant == 3) {
      rx = 110; ry = 173; eyeGap = 42; eyeYOff = -72; eyeR = 20;
      browGap = 31; browH = 13; noseW = 13; noseLen = 40;
      mouthW = 30; mouthH = 20; mouthY = 58; hairCut = 140;
      skinBase = 130; background = 3;
    } else if (variant == 4) {
      rx = 84; ry = 186; eyeGap = 34; eyeYOff = -42; eyeR = 11;
      browGap = 33; browH = 12; noseW = 12; noseLen = 76;
      noseShift = 14; mouthW = 22; mouthH = 8; mouthY = 38;
      mouthShift = -15; background = 4;
    } else if (variant == 5) {
      rx = 126; ry = 145; eyeGap = 53; eyeYOff = -44; eyeR = 16;
      browGap = 20; browH = 5; noseW = 6; noseLen = 80;
      mouthW = 46; mouthH = 16; mouthY = 66; hairCut = 60;
      skinBase = 185; background = 5;
    } else if (variant == 6) {
      rx = 101; ry = 152; eyeGap = 25; eyeYOff = -70; eyeR = 18;
      browGap = 28; browH = 16; noseW = 10; noseLen = 45;
      mouthW = 24; mouthH = 6; mouthY = 88; hairCut = 130;
      skinBase = 150; background = 6;
    } else if (variant == 9) {
      mouthH = 26; mouthY = 62;
    }
    int eyeY = (int) (cy + eyeYOff);

    for (int py = 0; py < HEIGHT; py++) {
      for (int px = 0; px < WIDTH; px++) {
        float nx = (px - cx) / rx;
        float ny = (py - cy) / ry;
        float r2 = nx * nx + ny * ny;
        float value = 48f + py * 0.05f;
        if (r2 < 1f) {
          value = skinBase - 25f * r2;
          if (py < cy - hairCut && r2 < 0.98f) value = 62f + (variant == 5 ? 20 : 0);
          if (distance(px, py, cx - eyeGap, eyeY) < eyeR
              || distance(px, py, cx + eyeGap, eyeY) < eyeR) {
            value = 35f;
          }
          if (py > eyeY - browH - 13 && py < eyeY - 13
              && Math.abs(px - (cx - eyeGap)) < browGap) value = 50f;
          if (py > eyeY - browH - 13 && py < eyeY - 13
              && Math.abs(px - (cx + eyeGap)) < browGap) value = 50f;
          if (Math.abs(px - (cx + noseShift + pose * 10)) < noseW
              && py > eyeY && py < eyeY + noseLen) value -= 28f;
          float mouthX = (px - (cx + mouthShift + pose * 12)) / (float) mouthW;
          float mouthYNorm = (py - (cy + mouthY)) / (float) mouthH;
          if (mouthX * mouthX + mouthYNorm * mouthYNorm < 1f) value = 88f;
          value += pose * (px - cx) * 0.08f;
        } else if (background == 1) {
          value = 100 + (px / 20) % 2 * 25;
        } else if (background == 2) {
          value = 30 + (py / 30) % 2 * 40;
        } else if (background == 3) {
          value = 70 + (px * 7 + py * 3) % 40;
        } else if (background == 4) {
          value = 20 + (px / 10) % 2 * 60;
        } else if (background == 5) {
          value = 140 - (px / 15) % 3 * 20;
        } else if (background == 6) {
          value = 55 + (int) (30 * Math.sin(px / 23.0));
        } else {
          value += ((px / 40 + py / 40) % 2 == 0) ? 18f : 0f;
        }
        value = (value - 128f) * contrast + 128f + brightness
            + (random.nextFloat() - 0.5f) * 6f;
        value = Math.max(0, Math.min(255, value));
        pixels[py * WIDTH + px] = (byte) (int) value;
      }
    }
    return pixels;
  }

  /** Scales a frame's light level (1 = full brightness, ~0.3 = night). */
  private static byte[] dim(byte[] frame, float scale) {
    byte[] result = frame.clone();
    for (int i = 0; i < WIDTH * HEIGHT; i++) {
      result[i] = (byte) (int) Math.max(0, Math.min(255, (frame[i] & 0xff) * scale));
    }
    return result;
  }

  private static float distance(float x, float y, float cx, float cy) {
    float dx = x - cx;
    float dy = y - cy;
    return (float) Math.sqrt(dx * dx + dy * dy);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
