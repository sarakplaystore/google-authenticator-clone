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

    for (int variant = 1; variant <= 6; variant++) {
      FaceTemplate other = FaceTemplate.fromFrame(
          face(0, 1.0f, 0, 0, 0, variant), WIDTH, HEIGHT, 0);
      require(other != null, "different face " + variant + " was null");
      float score = base.similarity(other);
      require(score < FaceTemplate.MATCH_THRESHOLD,
          "different face " + variant + " scored too high: " + score);
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

  private static float distance(float x, float y, float cx, float cy) {
    float dx = x - cx;
    float dy = y - cy;
    return (float) Math.sqrt(dx * dx + dy * dy);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
