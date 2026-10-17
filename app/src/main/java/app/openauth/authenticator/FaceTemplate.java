package app.openauth.authenticator;

/**
 * Compact on-device descriptor of a face region.
 *
 * <p>The descriptor is built from camera luminance frames and has three blocks:
 *
 * <ul>
 *   <li>a contrast-normalised {@value #GRID}x{@value #GRID} intensity grid, which keeps the
 *       overall shape of the face;</li>
 *   <li>a {@value #CELL_COUNT}x{@value #CELL_COUNT} grid of {@value #BIN_COUNT}-bin signed
 *       gradient histograms, which keeps the direction of the edges around the eyes, nose
 *       and mouth;</li>
 *   <li>a coarse map of the mean brightness of every cell, which keeps the proportions of
 *       the face.</li>
 * </ul>
 *
 * <p>Every block is scaled to unit length, so the cosine similarity of two descriptors is
 * the average similarity of the three blocks. No Android APIs are used here so that the
 * matching behaviour can be regression tested on a desktop JVM, see
 * {@code tests/FaceTemplateVectors.java}.
 */
public final class FaceTemplate {
  /** Resolution of the intensity grid. */
  public static final int GRID = 40;
  /** Number of cells per side of the gradient histogram grid. */
  public static final int CELL_COUNT = 10;
  /** Number of orientation bins per cell. */
  public static final int BIN_COUNT = 8;
  /** Length of the intensity block. */
  public static final int INTENSITY_LENGTH = GRID * GRID;
  /** Length of the gradient histogram block. */
  public static final int SHAPE_LENGTH = CELL_COUNT * CELL_COUNT * BIN_COUNT;
  /** Length of the coarse brightness block. */
  public static final int COARSE_LENGTH = CELL_COUNT * CELL_COUNT;
  /** Length of a complete descriptor. */
  public static final int LENGTH = INTENSITY_LENGTH + SHAPE_LENGTH + COARSE_LENGTH;
  /** Largest alignment offset, in grid cells, that is compensated while matching. */
  public static final int SHIFT_LIMIT = 3;

  private FaceTemplate() {}

  /**
   * Averages the given frame region down to a {@value #GRID}x{@value #GRID} grid of mean
   * luminance values.
   *
   * @param luminance frame luminance plane, row major, one byte per pixel
   * @param width frame width in pixels
   * @param height frame height in pixels
   * @param left region left edge, inclusive
   * @param top region top edge, inclusive
   * @param right region right edge, exclusive
   * @param bottom region bottom edge, exclusive
   * @return {@value #GRID}*{@value #GRID} mean luminance values
   */
  public static float[] downsample(byte[] luminance, int width, int height,
      int left, int top, int right, int bottom) {
    float[] grid = new float[GRID * GRID];
    int regionWidth = Math.max(1, right - left);
    int regionHeight = Math.max(1, bottom - top);
    for (int cellY = 0; cellY < GRID; cellY++) {
      int y0 = top + regionHeight * cellY / GRID;
      int y1 = top + regionHeight * (cellY + 1) / GRID;
      if (y1 <= y0) y1 = y0 + 1;
      for (int cellX = 0; cellX < GRID; cellX++) {
        int x0 = left + regionWidth * cellX / GRID;
        int x1 = left + regionWidth * (cellX + 1) / GRID;
        if (x1 <= x0) x1 = x0 + 1;
        float sum = 0f;
        int count = 0;
        for (int y = y0; y < y1 && y < height; y++) {
          int row = y * width;
          for (int x = x0; x < x1 && x < width; x++) {
            sum += luminance[row + x] & 0xff;
            count++;
          }
        }
        grid[cellY * GRID + cellX] = count == 0 ? 0f : sum / count;
      }
    }
    return grid;
  }

  /**
   * Removes the mean brightness and scales the grid to unit variance so that lighting
   * changes do not dominate the comparison.
   *
   * @param raw grid produced by {@link #downsample}
   * @return normalised grid of the same length
   */
  public static float[] normalize(float[] raw) {
    float[] normalised = new float[raw.length];
    float mean = 0f;
    for (float value : raw) mean += value;
    mean /= raw.length;
    float variance = 0f;
    for (float value : raw) {
      float delta = value - mean;
      variance += delta * delta;
    }
    float deviation = (float) Math.sqrt(variance / raw.length);
    if (deviation < 1f) deviation = 1f;
    for (int i = 0; i < raw.length; i++) normalised[i] = (raw[i] - mean) / deviation;
    return normalised;
  }

  /**
   * Builds a descriptor from a normalised grid.
   *
   * @param normalised grid produced by {@link #normalize}
   * @return descriptor of {@value #LENGTH} values
   */
  public static float[] describe(float[] normalised) {
    return describe(normalised, 0, 0);
  }

  /**
   * Builds a descriptor from a normalised grid, shifting the grid by the given number of
   * cells to compensate for small alignment differences between two captures.
   *
   * @param normalised grid produced by {@link #normalize}
   * @param shiftX horizontal shift in grid cells
   * @param shiftY vertical shift in grid cells
   * @return descriptor of {@value #LENGTH} values
   */
  public static float[] describe(float[] normalised, int shiftX, int shiftY) {
    float[] shifted = new float[GRID * GRID];
    for (int y = 0; y < GRID; y++) {
      int sourceY = y + shiftY;
      for (int x = 0; x < GRID; x++) {
        int sourceX = x + shiftX;
        float value = 0f;
        if (sourceX >= 0 && sourceX < GRID && sourceY >= 0 && sourceY < GRID) {
          value = normalised[sourceY * GRID + sourceX];
        }
        shifted[y * GRID + x] = value;
      }
    }
    float[] intensity = unitLength(shifted);
    float[] shape = unitLength(gradients(shifted));
    float[] coarse = unitLength(coarse(shifted));
    float[] descriptor = new float[LENGTH];
    System.arraycopy(intensity, 0, descriptor, 0, INTENSITY_LENGTH);
    System.arraycopy(shape, 0, descriptor, INTENSITY_LENGTH, SHAPE_LENGTH);
    System.arraycopy(coarse, 0, descriptor, INTENSITY_LENGTH + SHAPE_LENGTH, COARSE_LENGTH);
    return descriptor;
  }

  /**
   * Cosine similarity of two descriptors.
   *
   * @return similarity in the range -1 to 1
   */
  public static double similarity(float[] first, float[] second) {
    if (first == null || second == null || first.length != second.length) return 0.0;
    double dot = 0.0;
    double firstNorm = 0.0;
    double secondNorm = 0.0;
    for (int i = 0; i < first.length; i++) {
      dot += (double) first[i] * second[i];
      firstNorm += (double) first[i] * first[i];
      secondNorm += (double) second[i] * second[i];
    }
    if (firstNorm <= 0.0 || secondNorm <= 0.0) return 0.0;
    return dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
  }

  /**
   * Match score between a captured grid and the stored enrolment samples.
   *
   * <p>Every stored sample is compared at the best alignment, and the two strongest
   * samples are averaged. Requiring more than one sample to agree keeps a single noisy
   * frame from unlocking the app.
   *
   * @param samples stored enrolment descriptors
   * @param normalised grid of the current capture
   * @return match score, or 0 when there are no samples
   */
  public static double matchScore(float[][] samples, float[] normalised) {
    if (samples == null || samples.length == 0 || normalised == null) return 0.0;
    double[] bestPerSample = new double[samples.length];
    for (int i = 0; i < samples.length; i++) bestPerSample[i] = -1.0;
    for (int shiftY = -SHIFT_LIMIT; shiftY <= SHIFT_LIMIT; shiftY++) {
      for (int shiftX = -SHIFT_LIMIT; shiftX <= SHIFT_LIMIT; shiftX++) {
        float[] descriptor = describe(normalised, shiftX, shiftY);
        for (int i = 0; i < samples.length; i++) {
          double value = similarity(descriptor, samples[i]);
          if (value > bestPerSample[i]) bestPerSample[i] = value;
        }
      }
    }
    double first = -1.0;
    double second = -1.0;
    for (double value : bestPerSample) {
      if (value > first) {
        second = first;
        first = value;
      } else if (value > second) {
        second = value;
      }
    }
    if (second < 0.0) return Math.max(0.0, first);
    return (first + second) / 2.0;
  }

  /**
   * Averages stored descriptors into a single descriptor, used to keep a descriptor that
   * represents the whole enrolment.
   *
   * @param samples stored enrolment descriptors
   * @return averaged descriptor, or null when there are no samples
   */
  public static float[] average(float[][] samples) {
    if (samples == null || samples.length == 0) return null;
    float[] mean = new float[LENGTH];
    int used = 0;
    for (float[] sample : samples) {
      if (sample == null || sample.length != LENGTH) continue;
      used++;
      for (int i = 0; i < LENGTH; i++) mean[i] += sample[i];
    }
    if (used == 0) return null;
    for (int i = 0; i < LENGTH; i++) mean[i] /= used;
    return unitLength(mean);
  }

  /**
   * Rough quality score of a capture, used to tell the user when the face is not yet
   * usable. A score below one means the frame should be rejected.
   *
   * @param raw grid produced by {@link #downsample}
   * @return quality score, positive when the frame looks usable
   */
  public static double quality(float[] raw) {
    float mean = 0f;
    for (float value : raw) mean += value;
    mean /= raw.length;
    float variance = 0f;
    for (float value : raw) {
      float delta = value - mean;
      variance += delta * delta;
    }
    float deviation = (float) Math.sqrt(variance / raw.length);
    double score = 0.0;
    if (mean >= 40f && mean <= 215f) score += 1.0;
    if (deviation >= 8f) score += 1.0;
    if (deviation >= 18f) score += 0.5;
    return score;
  }

  /** Signed orientation gradient histograms of every cell of the grid. */
  private static float[] gradients(float[] grid) {
    float[] shape = new float[SHAPE_LENGTH];
    int cellSize = GRID / CELL_COUNT;
    for (int cellY = 0; cellY < CELL_COUNT; cellY++) {
      for (int cellX = 0; cellX < CELL_COUNT; cellX++) {
        float[] bins = new float[BIN_COUNT];
        for (int y = cellY * cellSize; y < (cellY + 1) * cellSize; y++) {
          for (int x = cellX * cellSize; x < (cellX + 1) * cellSize; x++) {
            float gx = sample(grid, x + 1, y) - sample(grid, x - 1, y);
            float gy = sample(grid, x, y + 1) - sample(grid, x, y - 1);
            float magnitude = (float) Math.sqrt(gx * gx + gy * gy);
            if (magnitude < 1e-4f) continue;
            double angle = Math.atan2(gy, gx);
            if (angle < 0) angle += 2.0 * Math.PI;
            double binSize = 2.0 * Math.PI / BIN_COUNT;
            double position = angle / binSize;
            int lower = (int) Math.floor(position) % BIN_COUNT;
            int upper = (lower + 1) % BIN_COUNT;
            double weight = position - Math.floor(position);
            bins[lower] += (float) (magnitude * (1.0 - weight));
            bins[upper] += (float) (magnitude * weight);
          }
        }
        float norm = 0f;
        for (float bin : bins) norm += bin * bin;
        norm = (float) Math.sqrt(norm);
        int offset = (cellY * CELL_COUNT + cellX) * BIN_COUNT;
        if (norm > 1e-6f) {
          for (int i = 0; i < BIN_COUNT; i++) shape[offset + i] = bins[i] / norm;
        }
      }
    }
    return shape;
  }

  /** Mean brightness of every cell of the grid. */
  private static float[] coarse(float[] grid) {
    float[] coarse = new float[COARSE_LENGTH];
    int cellSize = GRID / CELL_COUNT;
    for (int cellY = 0; cellY < CELL_COUNT; cellY++) {
      for (int cellX = 0; cellX < CELL_COUNT; cellX++) {
        float sum = 0f;
        int count = 0;
        for (int y = cellY * cellSize; y < (cellY + 1) * cellSize; y++) {
          for (int x = cellX * cellSize; x < (cellX + 1) * cellSize; x++) {
            sum += grid[y * GRID + x];
            count++;
          }
        }
        coarse[cellY * CELL_COUNT + cellX] = count == 0 ? 0f : sum / count;
      }
    }
    float mean = 0f;
    for (float value : coarse) mean += value;
    mean /= coarse.length;
    float variance = 0f;
    for (float value : coarse) {
      float delta = value - mean;
      variance += delta * delta;
    }
    float deviation = (float) Math.sqrt(variance / coarse.length);
    if (deviation < 1e-3f) deviation = 1e-3f;
    for (int i = 0; i < coarse.length; i++) coarse[i] = (coarse[i] - mean) / deviation;
    return coarse;
  }

  private static float sample(float[] grid, int x, int y) {
    if (x < 0 || x >= GRID || y < 0 || y >= GRID) return 0f;
    return grid[y * GRID + x];
  }

  private static float[] unitLength(float[] values) {
    float norm = 0f;
    for (float value : values) norm += value * value;
    norm = (float) Math.sqrt(norm);
    float[] result = new float[values.length];
    if (norm > 1e-6f) {
      for (int i = 0; i < values.length; i++) result[i] = values[i] / norm;
    }
    return result;
  }
}
