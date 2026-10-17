package app.openauth.authenticator;

/**
 * A small, self-contained face descriptor computed from a front-camera frame.
 *
 * <p>The descriptor combines an illumination-normalised thumbnail with a small
 * histogram-of-oriented-gradients grid. It is deliberately dependency-free so
 * that it can be unit tested on a plain JVM. No frame or image is ever stored;
 * only the numeric descriptor is kept, encrypted by {@link FaceStore}.</p>
 */
public final class FaceTemplate {
  /** Side of the normalised face crop used for feature extraction. */
  public static final int SIZE = 48;
  public static final int GRID = 6;
  public static final int BINS = 8;
  public static final int THUMB = 24;
  public static final int HOG_LENGTH = GRID * GRID * BINS;
  public static final int THUMB_LENGTH = THUMB * THUMB;
  public static final int BYTE_LENGTH = 4 + (HOG_LENGTH + THUMB_LENGTH) * 4;

  /** A frame must reach this score before it can count as the enrolled face. */
  public static final float MATCH_THRESHOLD = 0.83f;
  /**
   * Component floors for a strict (final-confirmation) match. A single blended
   * score can be reached by an impostor who matches one component well; the
   * strict check requires the gradient and thumbnail descriptors to agree
   * independently, which impostor faces do not manage (see the calibration
   * margins in tests/FaceTemplateVectors).
   */
  public static final float STRICT_HOG_FLOOR = 0.76f;
  public static final float STRICT_THUMB_FLOOR = 0.80f;
  /** Minimum usable quality; darker or flatter frames are rejected. */
  public static final float MIN_QUALITY = 0.36f;

  private static final int SERIAL_VERSION = 1;
  private static final float ELLIPSE_FEATHER = 4.0f;

  private final float[] hog;
  private final float[] thumb;
  private final float quality;

  private FaceTemplate(float[] hog, float[] thumb, float quality) {
    this.hog = hog;
    this.thumb = thumb;
    this.quality = quality;
  }

  /** Default crop position, as a fraction of the upright frame. */
  public static final float DEFAULT_CENTER_X = 0.5f;
  public static final float DEFAULT_CENTER_Y = 0.36f;
  /** Default crop side, as a fraction of the shorter upright frame side. */
  public static final float DEFAULT_SIDE = 0.72f;

  /**
   * Builds a descriptor from an NV21 preview frame. The crop is taken from the
   * middle of the upright frame. Returns {@code null} when the buffer is not a
   * usable camera frame.
   */
  public static FaceTemplate fromFrame(byte[] nv21, int width, int height, int rotation) {
    return fromFrame(nv21, width, height, rotation,
        DEFAULT_CENTER_X, DEFAULT_CENTER_Y, DEFAULT_SIDE);
  }

  /**
   * Builds a descriptor from a face-aligned crop. {@code centerX} and
   * {@code centerY} are fractions of the upright frame width and height;
   * {@code sideFraction} is the crop side relative to the shorter upright
   * side. The activity locks one such crop for a whole scan so that head
   * movement stays measurable instead of being followed by the crop.
   */
  public static FaceTemplate fromFrame(byte[] nv21, int width, int height, int rotation,
      float centerX, float centerY, float sideFraction) {
    if (nv21 == null || width < 64 || height < 64 || nv21.length < width * height) {
      return null;
    }
    float[] gray = sampleFace(nv21, width, height, rotation,
        centerX, centerY, sideFraction);
    if (gray == null) {
      return null;
    }
    return fromGray(gray);
  }

  /** Builds a descriptor from a square, upright grayscale crop. Used by tests. */
  static FaceTemplate fromGray(float[] gray) {
    if (gray == null || gray.length != SIZE * SIZE) {
      return null;
    }

    float[] mask = ellipseMask();
    double weightSum = 0.0;
    double mean = 0.0;
    for (int i = 0; i < gray.length; i++) {
      if (mask[i] > 0f) {
        weightSum += mask[i];
        mean += gray[i] * mask[i];
      }
    }
    if (weightSum < 1.0) {
      return null;
    }
    mean /= weightSum;

    double variance = 0.0;
    for (int i = 0; i < gray.length; i++) {
      if (mask[i] > 0f) {
        double delta = gray[i] - mean;
        variance += delta * delta * mask[i];
      }
    }
    variance /= weightSum;
    float std = (float) Math.sqrt(Math.max(0.0, variance));

    float quality = Math.min(1f, std / 48f);
    if (mean < 24.0 || mean > 236.0) {
      quality *= 0.45f;
    }
    if (std < 5.0f) {
      quality = 0f;
    }

    // Normalise illumination inside the elliptical face region, then soften
    // camera noise before measuring gradients.
    float[] normalized = new float[gray.length];
    float divisor = Math.max(std, 1.0f);
    for (int i = 0; i < gray.length; i++) {
      float value = mask[i] > 0f ? (gray[i] - (float) mean) / divisor : 0f;
      normalized[i] = Math.max(-3f, Math.min(3f, value));
    }
    float[] blurred = blur3x3(normalized);

    float[] hog = histogramOfGradients(blurred, mask);
    float[] thumb = thumbnail(blurred, mask);
    return new FaceTemplate(hog, thumb, quality);
  }

  public boolean isUsable() {
    return quality >= MIN_QUALITY;
  }

  public float getQuality() {
    return quality;
  }

  /**
   * Compares this descriptor with a stored one. Small ±1-cell / ±1-pixel shifts
   * are searched so that minor head movement does not cause a false rejection.
   * The result is between 0 and 1; higher is more similar.
   */
  public float similarity(FaceTemplate other) {
    float[] scores = componentScores(other);
    return Math.max(0f, Math.min(1f, 0.55f * scores[0] + 0.45f * scores[1]));
  }

  /**
   * Strict identity confirmation used for the final unlock decision: the
   * blended score must reach {@link #MATCH_THRESHOLD} and each descriptor
   * component must independently clear its floor.
   */
  public boolean matchesStrict(FaceTemplate other) {
    if (other == null) return false;
    float[] scores = componentScores(other);
    float blended = Math.max(0f, Math.min(1f, 0.55f * scores[0] + 0.45f * scores[1]));
    return blended >= MATCH_THRESHOLD
        && scores[0] >= STRICT_HOG_FLOOR
        && scores[1] >= STRICT_THUMB_FLOOR;
  }

  /** Package-private components for calibration tests: {gradient, thumbnail}. */
  float[] componentScores(FaceTemplate other) {
    if (other == null) return new float[] {0f, 0f};
    float hogScore = Math.max(0f, cosineShift(hog, other.hog, GRID, GRID, BINS, 0, 0));
    float bestThumb = 0f;
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        float score = cosineShift(thumb, other.thumb, THUMB, THUMB, 1, dx, dy);
        score *= 1f - 0.01f * (Math.abs(dx) + Math.abs(dy));
        if (score > bestThumb) bestThumb = score;
      }
    }
    return new float[] {hogScore, bestThumb};
  }

  /** Serialises the numeric descriptor for encrypted-at-rest storage. */
  public byte[] toBytes() {
    byte[] result = new byte[BYTE_LENGTH];
    writeInt(result, 0, SERIAL_VERSION);
    int offset = 4;
    for (float value : hog) {
      writeInt(result, offset, Float.floatToIntBits(value));
      offset += 4;
    }
    for (float value : thumb) {
      writeInt(result, offset, Float.floatToIntBits(value));
      offset += 4;
    }
    return result;
  }

  public static FaceTemplate fromBytes(byte[] data) {
    if (data == null || data.length != BYTE_LENGTH || readInt(data, 0) != SERIAL_VERSION) {
      return null;
    }
    float[] hog = new float[HOG_LENGTH];
    float[] thumb = new float[THUMB_LENGTH];
    int offset = 4;
    for (int i = 0; i < hog.length; i++) {
      hog[i] = Float.intBitsToFloat(readInt(data, offset));
      offset += 4;
    }
    for (int i = 0; i < thumb.length; i++) {
      thumb[i] = Float.intBitsToFloat(readInt(data, offset));
      offset += 4;
    }
    return new FaceTemplate(hog, thumb, 1f);
  }

  private static float[] sampleFace(byte[] frame, int width, int height, int rotation,
      float centerX, float centerY, float sideFraction) {
    return sampleFaceSized(frame, width, height, rotation, SIZE,
        centerX, centerY, sideFraction);
  }

  /**
   * Samples a face crop at an arbitrary resolution. Shared with
   * {@link FaceSignals}, which needs a larger crop than the descriptor for
   * reliable eye and mouth localisation. Returns a square grayscale image of
   * {@code size}x{@code size}, or {@code null} for an unusable buffer.
   *
   * <p>With the default crop ({@link #DEFAULT_CENTER_X}, {@link
   * #DEFAULT_CENTER_Y}, {@link #DEFAULT_SIDE}) the sample coordinates are the
   * same as the original fixed centre crop, so older registrations and the
   * synthetic regression vectors keep working unchanged.</p>
   */
  static float[] sampleFaceSized(byte[] frame, int width, int height, int rotation,
      int size) {
    return sampleFaceSized(frame, width, height, rotation, size,
        DEFAULT_CENTER_X, DEFAULT_CENTER_Y, DEFAULT_SIDE);
  }

  /** Crops a face with an explicit centre and side; see {@link #sampleFaceSized}. */
  static float[] sampleFaceSized(byte[] frame, int width, int height, int rotation,
      int size, float centerX, float centerY, float sideFraction) {
    if (frame == null || width < 64 || height < 64 || frame.length < width * height
        || size < 8) {
      return null;
    }
    int r = ((rotation % 360) + 360) % 360;
    int uprightWidth = (r == 90 || r == 270) ? height : width;
    int uprightHeight = (r == 90 || r == 270) ? width : height;
    float side = Math.min(uprightWidth, uprightHeight) * clamp(sideFraction, 0.20f, 1.40f);
    float left = clamp(centerX, 0f, 1f) * uprightWidth - side / 2f;
    float top = clamp(centerY, 0f, 1f) * uprightHeight - side / 2f;
    float step = side / size;

    float[] gray = new float[size * size];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        float sum = 0f;
        int count = 0;
        for (int sy = 0; sy < 3; sy++) {
          for (int sx = 0; sx < 3; sx++) {
            float ux = left + (x + (sx + 0.5f) / 3f) * step;
            float uy = top + (y + (sy + 0.5f) / 3f) * step;
            int sourceX;
            int sourceY;
            if (r == 90) {
              sourceX = (int) uy;
              sourceY = height - 1 - (int) ux;
            } else if (r == 180) {
              sourceX = width - 1 - (int) ux;
              sourceY = height - 1 - (int) uy;
            } else if (r == 270) {
              sourceX = width - 1 - (int) uy;
              sourceY = (int) ux;
            } else {
              sourceX = (int) ux;
              sourceY = (int) uy;
            }
            if (sourceX >= 0 && sourceX < width && sourceY >= 0 && sourceY < height) {
              sum += frame[sourceY * width + sourceX] & 0xff;
              count++;
            }
          }
        }
        gray[y * size + x] = count == 0 ? 0f : sum / count;
      }
    }
    return gray;
  }

  private static float[] ellipseMask() {
    float[] mask = new float[SIZE * SIZE];
    float center = (SIZE - 1) / 2f;
    float radius = SIZE / 2f;
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        float nx = (x - center) / radius;
        float ny = (y - center) / radius;
        float r2 = nx * nx + ny * ny;
        mask[y * SIZE + x] = Math.max(0f, Math.min(1f, (1f - r2) * ELLIPSE_FEATHER));
      }
    }
    return mask;
  }

  private static float[] blur3x3(float[] input) {
    float[] output = new float[input.length];
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        float sum = 0f;
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
          int yy = y + dy;
          if (yy < 0 || yy >= SIZE) continue;
          for (int dx = -1; dx <= 1; dx++) {
            int xx = x + dx;
            if (xx < 0 || xx >= SIZE) continue;
            int weight = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
            sum += input[yy * SIZE + xx] * weight;
            count += weight;
          }
        }
        output[y * SIZE + x] = sum / count;
      }
    }
    return output;
  }

  private static float[] histogramOfGradients(float[] gray, float[] mask) {
    float[] hog = new float[HOG_LENGTH];
    int cellSize = SIZE / GRID;
    for (int y = 1; y < SIZE - 1; y++) {
      for (int x = 1; x < SIZE - 1; x++) {
        float weight = mask[y * SIZE + x];
        if (weight <= 0f) continue;
        float gx = gray[y * SIZE + x + 1] - gray[y * SIZE + x - 1];
        float gy = gray[(y + 1) * SIZE + x] - gray[(y - 1) * SIZE + x];
        float magnitude = (float) Math.sqrt(gx * gx + gy * gy) * weight;
        if (magnitude < 1e-5f) continue;
        float angle = (float) Math.atan2(gy, gx);
        if (angle < 0f) angle += (float) Math.PI;
        int bin = (int) (angle / (float) Math.PI * BINS);
        if (bin >= BINS) bin = BINS - 1;
        int cellX = Math.min(GRID - 1, x / cellSize);
        int cellY = Math.min(GRID - 1, y / cellSize);
        hog[(cellY * GRID + cellX) * BINS + bin] += magnitude;
      }
    }
    // Normalise each cell so one high-contrast area cannot dominate.
    for (int cell = 0; cell < GRID * GRID; cell++) {
      float norm = 0f;
      int base = cell * BINS;
      for (int bin = 0; bin < BINS; bin++) norm += hog[base + bin] * hog[base + bin];
      norm = (float) Math.sqrt(norm);
      if (norm > 1e-5f) {
        for (int bin = 0; bin < BINS; bin++) hog[base + bin] /= norm;
      }
    }
    l2Normalize(hog);
    return hog;
  }

  private static float[] thumbnail(float[] gray, float[] mask) {
    float[] thumb = new float[THUMB_LENGTH];
    int cellSize = SIZE / THUMB;
    for (int y = 0; y < THUMB; y++) {
      for (int x = 0; x < THUMB; x++) {
        float sum = 0f;
        float weightSum = 0f;
        for (int dy = 0; dy < cellSize; dy++) {
          for (int dx = 0; dx < cellSize; dx++) {
            int px = x * cellSize + dx;
            int py = y * cellSize + dy;
            float weight = mask[py * SIZE + px];
            sum += gray[py * SIZE + px] * weight;
            weightSum += weight;
          }
        }
        thumb[y * THUMB + x] = weightSum > 1e-5f ? sum / weightSum : 0f;
      }
    }
    l2Normalize(thumb);
    return thumb;
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  private static void l2Normalize(float[] values) {
    float norm = 0f;
    for (float value : values) norm += value * value;
    norm = (float) Math.sqrt(norm);
    if (norm > 1e-5f) {
      for (int i = 0; i < values.length; i++) values[i] /= norm;
    }
  }

  /**
   * Cosine similarity over the overlapping part of two grids, where the second
   * grid is shifted by (dx, dy) in cells.
   */
  private static float cosineShift(float[] a, float[] b, int width, int height,
      int stride, int dx, int dy) {
    int x0 = Math.max(0, -dx);
    int x1 = Math.min(width, width - dx);
    int y0 = Math.max(0, -dy);
    int y1 = Math.min(height, height - dy);
    if (x0 >= x1 || y0 >= y1) return 0f;

    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        int aBase = (y * width + x) * stride;
        int bBase = ((y + dy) * width + (x + dx)) * stride;
        for (int i = 0; i < stride; i++) {
          float av = a[aBase + i];
          float bv = b[bBase + i];
          dot += av * bv;
          normA += av * av;
          normB += bv * bv;
        }
      }
    }
    if (normA < 1e-9 || normB < 1e-9) return 0f;
    return (float) (dot / Math.sqrt(normA * normB));
  }

  private static void writeInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static int readInt(byte[] source, int offset) {
    return ((source[offset] & 0xff) << 24)
        | ((source[offset + 1] & 0xff) << 16)
        | ((source[offset + 2] & 0xff) << 8)
        | (source[offset + 3] & 0xff);
  }
}
