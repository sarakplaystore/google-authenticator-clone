import app.openauth.authenticator.FaceTemplate;
import app.openauth.authenticator.FaceThreshold;
import java.util.Random;

/**
 * Dependency-free regression test for the on-device face descriptor. Run against the
 * compiled app classes:
 *
 * <pre>
 * mkdir -p build/test-classes
 * javac -cp build/manual/classes -d build/test-classes tests/FaceTemplateVectors.java
 * java -cp build/manual/classes:build/test-classes FaceTemplateVectors
 * </pre>
 *
 * <p>Synthetic faces are rendered with per person proportions, then captured again under
 * different lighting, noise and small alignment shifts. The test requires the same person
 * to stay above the shipped threshold and a different person to stay below it.
 */
public final class FaceTemplateVectors {
  private static final int WIDTH = 240;
  private static final int HEIGHT = 320;
  private static final int REGION_LEFT = 40;
  private static final int REGION_TOP = 40;
  private static final int REGION_RIGHT = 200;
  private static final int REGION_BOTTOM = 200;
  private static final int SAMPLE_COUNT = 5;
  private static final int PEOPLE = 8;

  public static void main(String[] args) {
    Person[] people = new Person[PEOPLE];
    for (int i = 0; i < PEOPLE; i++) {
      people[i] = new Person(new Random(100 + i * 7919L));
    }

    float[][][] enrolments = new float[PEOPLE][][];
    for (int i = 0; i < PEOPLE; i++) {
      float[][] samples = enrol(people[i], 1000L * (i + 1));
      float[][] stored = new float[samples.length + 1][];
      System.arraycopy(samples, 0, stored, 0, samples.length);
      stored[samples.length] = FaceTemplate.average(samples);
      enrolments[i] = stored;
    }

    double lowestMatch = 1.0;
    for (int i = 0; i < PEOPLE; i++) {
      double score = worstCase(enrolments[i], people[i]);
      lowestMatch = Math.min(lowestMatch, score);
    }

    double highestReject = 0.0;
    for (int i = 0; i < PEOPLE; i++) {
      for (int j = 0; j < PEOPLE; j++) {
        if (i == j) continue;
        double score = bestCross(enrolments[i], people[j]);
        highestReject = Math.max(highestReject, score);
      }
    }

    System.out.printf("same person, new capture : %.3f (worst of %d people)%n",
        lowestMatch, PEOPLE);
    System.out.printf("different people           : %.3f (worst pair)%n", highestReject);

    double threshold = FaceThreshold.VALUE;
    if (lowestMatch <= highestReject) {
      throw new AssertionError("Match and reject ranges overlap: " + lowestMatch
          + " vs " + highestReject);
    }
    if (threshold <= highestReject) {
      throw new AssertionError("Threshold " + threshold + " is too low, a different person "
          + "scores " + highestReject);
    }
    if (threshold >= lowestMatch) {
      throw new AssertionError("Threshold " + threshold + " is too high, the same person "
          + "only scores " + lowestMatch);
    }

    // The descriptor keeps a stable shape with three unit length blocks.
    float[] descriptor = FaceTemplate.describe(FaceTemplate.normalize(
        FaceTemplate.downsample(render(people[0], 1.0, 0.0, 0.0, 0, 0, new Random(3L)),
            WIDTH, HEIGHT, REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM)));
    if (descriptor.length != FaceTemplate.LENGTH) {
      throw new AssertionError("Unexpected descriptor length " + descriptor.length);
    }
    checkBlock(descriptor, 0, FaceTemplate.INTENSITY_LENGTH, "intensity");
    checkBlock(descriptor, FaceTemplate.INTENSITY_LENGTH, FaceTemplate.SHAPE_LENGTH, "gradient");
    checkBlock(descriptor, FaceTemplate.INTENSITY_LENGTH + FaceTemplate.SHAPE_LENGTH,
        FaceTemplate.COARSE_LENGTH, "coarse");

    // A frame that is too dark or too flat must be rejected.
    byte[] flat = new byte[WIDTH * HEIGHT];
    double quality = FaceTemplate.quality(FaceTemplate.downsample(flat, WIDTH, HEIGHT,
        REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM));
    if (quality > 0.0) {
      throw new AssertionError("A flat frame should not be accepted, quality " + quality);
    }

    // A well lit frame is accepted.
    double goodQuality = FaceTemplate.quality(FaceTemplate.downsample(
        render(people[0], 1.0, 0.0, 0.0, 0, 0, new Random(3L)), WIDTH, HEIGHT,
        REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM));
    if (goodQuality < 2.0) {
      throw new AssertionError("A clear frame should be accepted, quality " + goodQuality);
    }

    System.out.printf("Threshold %.2f accepted, margin below %.3f and above %.3f.%n",
        threshold, lowestMatch - threshold, threshold - highestReject);
    System.out.println("Passed face descriptor checks.");
  }

  private static void checkBlock(float[] descriptor, int offset, int length, String name) {
    double norm = 0.0;
    for (int i = offset; i < offset + length; i++) norm += descriptor[i] * descriptor[i];
    if (Math.abs(Math.sqrt(norm) - 1.0) > 0.001) {
      throw new AssertionError("The " + name + " block is not unit length");
    }
  }

  private static float[][] enrol(Person person, long seed) {
    float[][] samples = new float[SAMPLE_COUNT][];
    for (int i = 0; i < SAMPLE_COUNT; i++) {
      Random random = new Random(seed + i);
      byte[] frame = render(person, 0.75 + random.nextDouble() * 0.5,
          -18 + random.nextDouble() * 36, random.nextDouble() * 6.0,
          random.nextInt(9) - 4, random.nextInt(9) - 4, random);
      samples[i] = FaceTemplate.describe(FaceTemplate.normalize(
          FaceTemplate.downsample(frame, WIDTH, HEIGHT, REGION_LEFT, REGION_TOP,
              REGION_RIGHT, REGION_BOTTOM)));
    }
    return samples;
  }

  /** Lowest similarity between the stored samples and new captures of the same person. */
  private static double worstCase(float[][] samples, Person person) {
    double worst = 1.0;
    for (int i = 0; i < 5; i++) {
      Random random = new Random(9000L + i);
      byte[] frame = render(person, 0.8 + random.nextDouble() * 0.4,
          -12 + random.nextDouble() * 24, random.nextDouble() * 4.0,
          random.nextInt(7) - 3, random.nextInt(7) - 3, random);
      float[] grid = FaceTemplate.normalize(FaceTemplate.downsample(frame, WIDTH, HEIGHT,
          REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM));
      worst = Math.min(worst, FaceTemplate.matchScore(samples, grid));
    }
    return worst;
  }

  /** Highest match score between the samples of one person and captures of another. */
  private static double bestCross(float[][] first, Person other) {
    double best = 0.0;
    for (int i = 0; i < 5; i++) {
      Random random = new Random(7000L + i);
      byte[] frame = render(other, 0.8 + random.nextDouble() * 0.4,
          -12 + random.nextDouble() * 24, random.nextDouble() * 4.0,
          random.nextInt(7) - 3, random.nextInt(7) - 3, random);
      float[] grid = FaceTemplate.normalize(FaceTemplate.downsample(frame, WIDTH, HEIGHT,
          REGION_LEFT, REGION_TOP, REGION_RIGHT, REGION_BOTTOM));
      best = Math.max(best, FaceTemplate.matchScore(first, grid));
    }
    return best;
  }

  /** Renders a synthetic face into a luminance frame. */
  private static byte[] render(Person person, double lightScale, double lightOffset,
      double noise, int shiftX, int shiftY, Random random) {
    byte[] frame = new byte[WIDTH * HEIGHT];
    double centreX = WIDTH / 2.0 + shiftX;
    double centreY = HEIGHT / 2.0 + shiftY;
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        double nx = (x - centreX) / (WIDTH * person.headWidth);
        double ny = (y - centreY) / (HEIGHT * person.headHeight);
        double value;
        double taper = 1.0 - person.jaw * Math.max(0, ny) * 0.9;
        double distance = (nx / taper) * (nx / taper) + ny * ny;
        if (distance <= 1.0) {
          value = person.skin;
          if (ny < -1.0 + person.hairTop || (Math.abs(nx) > 0.72 - person.hairSide && ny < 0.1)) {
            value = person.skin * 0.42;
          } else {
            double brow = Math.pow((Math.abs(nx) - person.eyeGap) / 0.2, 2.0)
                + Math.pow((ny + 0.34) / person.brow, 2.0);
            double eye = Math.pow((Math.abs(nx) - person.eyeGap) / (0.15 * person.eyeSize), 2.0)
                + Math.pow((ny + 0.18) / (0.07 * person.eyeSize), 2.0);
            double nose = Math.pow(nx / person.noseWidth, 2.0)
                + Math.pow((ny - person.noseLength) / (0.12 + person.noseLength), 2.0);
            double mouth = Math.pow((ny - 0.5 + person.mouthCurve * 0.06 * nx * nx) / person.lip, 2.0)
                + Math.pow(nx / person.mouthWidth, 2.0);
            if (brow <= 1.0) value = person.skin * 0.55;
            if (eye <= 1.0) value = person.skin * 0.25;
            else if (mouth <= 1.0) value = person.skin * 0.5;
            else if (nose <= 1.0) value = person.skin * 0.88;
            else value = person.skin * (1.0 - 0.1 * Math.exp(-distance)
                + 0.04 * Math.sin(nx * 9 + person.seed));
          }
        } else {
          value = 50 + 16 * Math.sin(x / 37.0 + person.seed) + 9 * Math.cos(y / 51.0);
        }
        value = value * lightScale + lightOffset + random.nextGaussian() * noise;
        frame[y * WIDTH + x] = (byte) Math.max(0, Math.min(255, Math.round(value)));
      }
    }
    return frame;
  }

  /** A synthetic face with its own proportions. */
  private static final class Person {
    final double headWidth;
    final double headHeight;
    final double jaw;
    final double hairTop;
    final double hairSide;
    final double eyeGap;
    final double eyeSize;
    final double brow;
    final double noseWidth;
    final double noseLength;
    final double mouthWidth;
    final double mouthCurve;
    final double lip;
    final double skin;
    final double seed;

    Person(Random random) {
      headWidth = 0.58 + random.nextDouble() * 0.16;
      headHeight = 0.66 + random.nextDouble() * 0.18;
      jaw = 0.55 + random.nextDouble() * 0.4;
      hairTop = 0.35 + random.nextDouble() * 0.4;
      hairSide = random.nextDouble() * 0.5;
      eyeGap = 0.15 + random.nextDouble() * 0.14;
      eyeSize = 0.8 + random.nextDouble() * 0.6;
      brow = 0.1 + random.nextDouble() * 0.2;
      noseWidth = 0.07 + random.nextDouble() * 0.08;
      noseLength = 0.1 + random.nextDouble() * 0.12;
      mouthWidth = 0.16 + random.nextDouble() * 0.16;
      mouthCurve = random.nextDouble() * 2.0 - 1.0;
      lip = 0.04 + random.nextDouble() * 0.05;
      skin = 120 + random.nextDouble() * 90;
      seed = random.nextDouble() * 1000.0;
    }
  }
}
