package app.openauth.authenticator;

/**
 * Per-frame liveness signals measured from the centred face crop.
 *
 * <p>The identity descriptor ({@link FaceTemplate}) deliberately works on a
 * tiny 48×48 normalised crop. That is too coarse to tell a living face from a
 * photograph, so this class analyses a 96×96 crop of the same region and
 * locates the dark eye and mouth blobs — the most reliable landmarks available
 * without a model — together with two passive anti-spoofing measures:</p>
 *
 * <ul>
 *   <li><b>Eye openness</b> — how much dark, vertically thick mass each eye blob
 *       has. A blink collapses it towards a thin line; a printed photo cannot
 *       blink at all.</li>
 *   <li><b>Feature geometry</b> — eye midpoint, eye gap and mouth centroid in
 *       crop-relative units. On a real 3D head the mouth shifts further than
 *       the eye midpoint during a turn (parallax); on a flat photo every
 *       feature moves rigidly together.</li>
 *   <li><b>Screen moiré</b> — a replayed phone/tablet display produces a
 *       regular two-dimensional interference grid. Strong periodicity in both
 *       the row and column means at short lags is characteristic of a screen,
 *       not of skin (lighting flicker banding is one-dimensional and
 *       low-frequency, so it does not trigger this).</li>
 *   <li><b>Face luminance</b> — mean light over the face ellipse, used to
 *       check that the face brightens when the app flashes the screen at
 *       random times. An emissive replay screen does not respond.</li>
 * </ul>
 *
 * <p>Landmark thresholds are stated in <em>canonical</em> contrast units: the
 * crop is re-scaled to a reference dynamic range before the eyes and mouth
 * are searched, so a face lit only by the screen at night measures like its
 * well-lit twin instead of falling below fixed brightness cliffs. The
 * luminance reported for the flash check is always the raw scene value.</p>
 *
 * <p>The class is pure Java over {@code float[]} images so the geometry can be
 * unit tested on a plain JVM with synthetic faces.</p>
 */
public final class FaceSignals {
  /** Side of the crop analysed for liveness (descriptor uses 48). */
  public static final int SIZE = 96;

  /**
   * Canonical crop contrast (std) the landmarks are measured at. A well-lit
   * face sits near this level, so the historical thresholds keep their
   * meaning while dim night frames are lifted to the same scale.
   */
  private static final float CANONICAL_STD = 48f;
  /**
   * Amplification cap for nearly flat crops: dividing by a tiny std would
   * turn sensor noise into fake structure. Below roughly this raw contrast
   * there is nothing reliable to amplify.
   */
  private static final float MIN_RAW_STD = 8f;

  // Landmark search windows in 96×96 crop coordinates. They are deliberately
  // generous: the face may sit nearer or further from the camera, which moves
  // the eyes by several pixels between sessions. The blob finder below first
  // localises coarsely inside the window, then re-centres on the darkest
  // cluster, so brows or hair entering the window do not dominate.
  // Windows overlap the centre so the eyes stay reachable at the extremes
  // of a turn or nod; the pair check (level, plausible gap) rejects both
  // windows locking onto the same eye.
  private static final int EYE_Y0 = 20, EYE_Y1 = 64;
  private static final int EYE_L_X0 = 8, EYE_L_X1 = 52;
  private static final int EYE_R_X0 = 44, EYE_R_X1 = 88;
  private static final int MOUTH_Y0 = 60, MOUTH_Y1 = 91;
  private static final int MOUTH_X0 = 27, MOUTH_X1 = 69;

  private static final int LOCAL_HALF_X = 9;
  private static final int LOCAL_HALF_Y = 7;

  /** True when both eye blobs were located and form a plausible pair. */
  public final boolean eyesFound;
  /** Eye midpoint and gap in crop-relative units (0..1). -1 when not found. */
  public final float eyeMidX;
  public final float eyeMidY;
  public final float eyeGap;
  /**
   * Combined eye openness, roughly 0 (closed line) to 1 (wide open). Only
   * meaningful relative to the same person's open-eye baseline in a session,
   * which is how {@link LivenessSession} uses it.
   */
  public final float eyeOpenness;
  /** True when a plausible mouth blob was located. */
  public final boolean mouthFound;
  public final float mouthX;
  public final float mouthY;
  /** Mean luma (0..255) over the elliptical face region, in raw scene units. */
  public final float meanLuma;
  /** 0..1 strength of two-dimensional short-period grid interference. */
  public final float moireScore;

  private FaceSignals(boolean eyesFound, float eyeMidX, float eyeMidY, float eyeGap,
      float eyeOpenness, boolean mouthFound, float mouthX, float mouthY,
      float meanLuma, float moireScore) {
    this.eyesFound = eyesFound;
    this.eyeMidX = eyeMidX;
    this.eyeMidY = eyeMidY;
    this.eyeGap = eyeGap;
    this.eyeOpenness = eyeOpenness;
    this.mouthFound = mouthFound;
    this.mouthX = mouthX;
    this.mouthY = mouthY;
    this.meanLuma = meanLuma;
    this.moireScore = moireScore;
  }

  /** Analyses an NV21 preview frame. Returns {@code null} for a bad buffer. */
  public static FaceSignals fromFrame(byte[] nv21, int width, int height, int rotation) {
    float[] gray = FaceTemplate.sampleFaceSized(nv21, width, height, rotation, SIZE);
    if (gray == null) return null;
    return fromGray(gray);
  }

  /**
   * Analyses the same frame using an explicit face crop. The activity locks a
   * crop once per scan so every landmark is measured in one stable
   * coordinate frame; following the detected face on every frame would
   * cancel out the head movement the liveness challenges are meant to see.
   */
  public static FaceSignals fromFrame(byte[] nv21, int width, int height, int rotation,
      float centerX, float centerY, float sideFraction) {
    float[] gray = FaceTemplate.sampleFaceSized(nv21, width, height, rotation, SIZE,
        centerX, centerY, sideFraction);
    if (gray == null) return null;
    return fromGray(gray);
  }

  /** Analyses a 96×96 grayscale crop. Separated for plain-JVM tests. */
  public static FaceSignals fromGray(float[] gray) {
    if (gray == null || gray.length != SIZE * SIZE) return null;

    // Luminance for the flash-response check is always measured on the raw
    // scene; only the landmark search below works on the normalised copy.
    float meanLuma = faceMeanLuma(gray);

    // Re-scale the crop to a canonical dynamic range. A face in a dark room
    // has the same structure as a bright one with every difference scaled
    // down; without this, fixed contrast thresholds lose the eyes at night
    // and the face can never unlock. The divisor floor keeps a nearly flat,
    // noise-only crop from being amplified into fake landmarks.
    double sum = 0.0;
    for (int i = 0; i < gray.length; i++) sum += gray[i];
    float mean = (float) (sum / gray.length);
    double variance = 0.0;
    for (int i = 0; i < gray.length; i++) {
      double delta = gray[i] - mean;
      variance += delta * delta;
    }
    float std = (float) Math.sqrt(variance / gray.length);
    float scale = CANONICAL_STD / Math.max(std, MIN_RAW_STD);
    float[] norm = new float[SIZE * SIZE];
    for (int i = 0; i < gray.length; i++) {
      float value = (gray[i] - mean) * scale;
      norm[i] = Math.max(-3f * CANONICAL_STD, Math.min(3f * CANONICAL_STD, value));
    }

    Blob leftEye = findEye(norm, EYE_L_X0, EYE_L_X1, EYE_Y0, EYE_Y1);
    Blob rightEye = findEye(norm, EYE_R_X0, EYE_R_X1, EYE_Y0, EYE_Y1);
    boolean eyes = leftEye != null && rightEye != null
        && Math.abs(leftEye.y - rightEye.y) <= 8f;
    float gap = eyes ? rightEye.x - leftEye.x : -1f;
    if (eyes && (gap < 15f || gap > 47f)) eyes = false;

    Blob mouth = findBlob(norm, MOUTH_X0, MOUTH_X1, MOUTH_Y0, MOUTH_Y1, 0.45f);
    boolean mouthOk = mouth != null && mouth.darkPixels >= 14;

    float inv = 1f / SIZE;
    return new FaceSignals(
        eyes,
        eyes ? (leftEye.x + rightEye.x) / 2f * inv : -1f,
        eyes ? (leftEye.y + rightEye.y) / 2f * inv : -1f,
        eyes ? gap * inv : -1f,
        eyes ? (leftEye.openness + rightEye.openness) / 2f : 0f,
        mouthOk,
        mouthOk ? mouth.x * inv : -1f,
        mouthOk ? mouth.y * inv : -1f,
        meanLuma,
        moireScore(norm));
  }

  /**
   * Coarse "a face is plausibly here" test used only when the platform face
   * detector reports nothing on a device (some builds lack the native
   * detector library). It requires a level eye pair with a mouth below it at
   * a face-like distance, roughly centred in the crop.
   */
  public boolean geometryLooksLikeFace() {
    if (!eyesFound || !mouthFound) return false;
    if (Math.abs(eyeMidX - 0.5f) > 0.16f) return false;
    if (eyeMidY < 0.26f || eyeMidY > 0.59f) return false;
    if (eyeGap < 0.16f || eyeGap > 0.49f) return false;
    // The upper bound is generous because during a blink the eye point can
    // jump up to the brow, stretching the eye-to-mouth distance briefly;
    // those frames must still count as "face present".
    float eyeToMouth = mouthY - eyeMidY;
    return eyeToMouth >= 0.13f && eyeToMouth <= 0.60f
        && Math.abs(mouthX - eyeMidX) <= 0.17f;
  }

  // Centre-surround eye box: an eye is a compact dark blob surrounded by
  // brighter skin. A hairline or fringe is a wide dark band whose surround
  // is dark too, so it scores low here even though it beats the eye on raw
  // darkness. Openness comes from that same local contrast: a closed lid is
  // skin with a thin lash line, so the contrast collapses on a blink.
  private static final int EYE_BOX_HALF_X = 7;
  private static final int EYE_BOX_HALF_Y = 5;
  private static final int EYE_RING_HALF_X = 9;
  private static final int EYE_RING_HALF_Y = 8;

  private static Blob findEye(float[] gray, int x0, int x1, int y0, int y1) {
    float bestScore = 0f;
    float bestContrast = 0f;
    int bestX = -1;
    int bestY = -1;
    float bestCenterMean = 0f;
    float bestBorderMean = 0f;
    int centerArea = (2 * EYE_BOX_HALF_X + 1) * (2 * EYE_BOX_HALF_Y + 1);
    int ringArea = (2 * EYE_RING_HALF_X + 1) * (2 * EYE_RING_HALF_Y + 1) - centerArea;
    for (int cy = y0 + EYE_RING_HALF_Y; cy < y1 - EYE_RING_HALF_Y; cy += 2) {
      for (int cx = x0 + EYE_RING_HALF_X; cx < x1 - EYE_RING_HALF_X; cx += 2) {
        float centerSum = 0f;
        float ringSum = 0f;
        for (int y = cy - EYE_RING_HALF_Y; y <= cy + EYE_RING_HALF_Y; y++) {
          for (int x = cx - EYE_RING_HALF_X; x <= cx + EYE_RING_HALF_X; x++) {
            float value = gray[y * SIZE + x];
            ringSum += value;
            if (Math.abs(x - cx) <= EYE_BOX_HALF_X && Math.abs(y - cy) <= EYE_BOX_HALF_Y) {
              centerSum += value;
            }
          }
        }
        float centerMean = centerSum / centerArea;
        float borderMean = (ringSum - centerSum) / ringArea;
        float contrast = borderMean - centerMean;
        // Mild downward preference: when brow and eye score similarly the
        // eye is the lower one.
        float rowBias = 0.78f + 0.44f * (cy - y0) / Math.max(1f, (float) (y1 - y0));
        float score = contrast * rowBias;
        if (score > bestScore) {
          // An eye box is only partly dark; a box that is almost entirely
          // dark is hair, however strong its centre-surround contrast at
          // the hairline edge.
          float threshold = (centerMean + borderMean) / 2f;
          int dark = 0;
          for (int y = cy - EYE_BOX_HALF_Y; y <= cy + EYE_BOX_HALF_Y; y++) {
            for (int x = cx - EYE_BOX_HALF_X; x <= cx + EYE_BOX_HALF_X; x++) {
              if (gray[y * SIZE + x] < threshold) dark++;
            }
          }
          if (dark > 0.62f * centerArea) continue;
          bestScore = score;
          bestContrast = contrast;
          bestX = cx;
          bestY = cy;
          bestCenterMean = centerMean;
          bestBorderMean = borderMean;
        }
      }
    }
    if (bestX < 0 || bestContrast < 15f) return null;

    // Refine to the darkness-weighted centroid inside the winning box and
    // measure the blob's vertical thickness.
    float threshold = (bestCenterMean + bestBorderMean) / 2f;
    float weightedX = 0f;
    float weightedY = 0f;
    float total = 0f;
    int dark = 0;
    for (int y = bestY - EYE_BOX_HALF_Y; y <= bestY + EYE_BOX_HALF_Y; y++) {
      for (int x = bestX - EYE_BOX_HALF_X; x <= bestX + EYE_BOX_HALF_X; x++) {
        float value = gray[y * SIZE + x];
        if (value < threshold) {
          float weight = threshold - value;
          weightedX += x * weight;
          weightedY += y * weight;
          total += weight;
          dark++;
        }
      }
    }
    Blob blob = new Blob();
    if (dark >= 6 && total > 0f) {
      blob.x = weightedX / total;
      blob.y = weightedY / total;
      // Thickness over a narrow band around the centroid only, so the brow
      // (a few pixels above the eye) cannot inflate it and mask a blink.
      float yVariance = 0f;
      float bandTotal = 0f;
      int bandTop = Math.max(bestY - EYE_BOX_HALF_Y, Math.round(blob.y) - 3);
      int bandBottom = Math.min(bestY + EYE_BOX_HALF_Y, Math.round(blob.y) + 3);
      for (int y = bandTop; y <= bandBottom; y++) {
        for (int x = bestX - EYE_BOX_HALF_X; x <= bestX + EYE_BOX_HALF_X; x++) {
          float value = gray[y * SIZE + x];
          if (value < threshold) {
            float delta = y - blob.y;
            yVariance += (threshold - value) * delta * delta;
            bandTotal += threshold - value;
          }
        }
      }
      if (bandTotal > 0f) {
        yVariance /= bandTotal;
      } else {
        yVariance = 0f;
      }
      float thickness = (float) Math.sqrt(yVariance);
      blob.openness = clamp01(
          0.70f * clamp01(bestContrast / 52f)
              + 0.30f * clamp01((thickness - 0.4f) / 1.7f));
    } else {
      blob.x = bestX;
      blob.y = bestY;
      blob.openness = clamp01(bestContrast / 52f);
    }
    blob.darkPixels = Math.max(dark, 10);
    return blob;
  }

  /** A localised dark feature blob with its openness estimate. */
  private static final class Blob {
    float x;
    float y;
    float openness;
    int darkPixels;
  }

  /**
   * Finds the darkest cluster inside a window. Pixels darker than
   * {@code mean - k·std} vote with weight proportional to their darkness; a
   * second pass restricted to a small box around the first centroid keeps a
   * brow or hairline at the window edge from pulling the result away.
   */
  private static Blob findBlob(float[] gray, int x0, int x1, int y0, int y1,
      float thresholdK) {
    double sum = 0.0;
    int count = 0;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        sum += gray[y * SIZE + x];
        count++;
      }
    }
    if (count == 0) return null;
    double mean = sum / count;
    double variance = 0.0;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        double delta = gray[y * SIZE + x] - mean;
        variance += delta * delta;
      }
    }
    float std = (float) Math.sqrt(variance / count);
    if (std < 4f) return null;
    float threshold = (float) mean - thresholdK * std;

    // First pass: coarse centroid of all dark pixels in the window.
    float cx = 0f;
    float cy = 0f;
    float total = 0f;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        float value = gray[y * SIZE + x];
        if (value < threshold) {
          float weight = threshold - value;
          cx += x * weight;
          cy += y * weight;
          total += weight;
        }
      }
    }
    if (total <= 0f) return null;
    cx /= total;
    cy /= total;

    // Second pass: localise inside a small box around the coarse centroid
    // and measure the blob's darkness share and vertical thickness. An open
    // eye is a compact dark blob several pixels thick; a closed eye is a
    // thin, weaker line, so both measures fall together on a blink.
    int lx0 = Math.max(x0, Math.round(cx) - LOCAL_HALF_X);
    int lx1 = Math.min(x1, Math.round(cx) + LOCAL_HALF_X + 1);
    int ly0 = Math.max(y0, Math.round(cy) - LOCAL_HALF_Y);
    int ly1 = Math.min(y1, Math.round(cy) + LOCAL_HALF_Y + 1);
    float weightedX = 0f;
    float weightedY = 0f;
    float weightedTotal = 0f;
    int dark = 0;
    for (int y = ly0; y < ly1; y++) {
      for (int x = lx0; x < lx1; x++) {
        float value = gray[y * SIZE + x];
        if (value < threshold) {
          float weight = threshold - value;
          weightedX += x * weight;
          weightedY += y * weight;
          weightedTotal += weight;
          dark++;
        }
      }
    }
    if (dark < 9 || weightedTotal <= 0f) return null;
    float bx = weightedX / weightedTotal;
    float by = weightedY / weightedTotal;
    float yVariance = 0f;
    for (int y = ly0; y < ly1; y++) {
      for (int x = lx0; x < lx1; x++) {
        float value = gray[y * SIZE + x];
        if (value < threshold) {
          float weight = threshold - value;
          float delta = y - by;
          yVariance += weight * delta * delta;
        }
      }
    }
    float thickness = (float) Math.sqrt(yVariance / weightedTotal);
    int localArea = Math.max(1, (lx1 - lx0) * (ly1 - ly0));
    float darkFraction = dark / (float) localArea;

    Blob blob = new Blob();
    blob.x = bx;
    blob.y = by;
    blob.darkPixels = dark;
    blob.openness = clamp01(
        0.55f * clamp01(darkFraction / 0.26f)
            + 0.45f * clamp01((thickness - 0.45f) / 1.5f));
    return blob;
  }

  private static float faceMeanLuma(float[] gray) {
    float centre = (SIZE - 1) / 2f;
    double sum = 0.0;
    double weight = 0.0;
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        float nx = (x - centre) / (SIZE / 2f);
        float ny = (y - centre) / (SIZE / 2f);
        float r2 = nx * nx + ny * ny;
        if (r2 < 0.72f) {
          float w = 1f - r2;
          sum += gray[y * SIZE + x] * w;
          weight += w;
        }
      }
    }
    return weight > 0.0 ? (float) (sum / weight) : 0f;
  }

  /**
   * Detects the regular interference grid produced when a camera photographs
   * an LCD/OLED display. Every row and column crossing the face is
   * detrended and autocorrelated at short lags (2..5 px); a screen grid
   * makes nearly all of them oscillate, so the <em>median</em> peak is high
   * on both axes, while skin rows are smooth and mains-flicker banding is
   * one-dimensional with a much longer period.
   */
  private static float moireScore(float[] gray) {
    float rowPeak = medianLinePeak(gray, true);
    float colPeak = medianLinePeak(gray, false);
    return clamp01(Math.min(rowPeak, colPeak) * 1.9f);
  }

  /**
   * Median periodicity across all rows (or columns) crossing the face.
   * Autocorrelations are gathered per lag and median-filtered across lines
   * before the peak is taken: isolated edges and sensor noise peak at
   * random lags on random lines and median away, while a screen grid peaks
   * at the same lag almost everywhere.
   */
  private static float medianLinePeak(float[] gray, boolean rows) {
    int lo = 20;
    int hi = SIZE - 20;
    int n = hi - lo;
    int minLag = 3;
    int maxLag = 11;
    int lagCount = maxLag - minLag + 1;
    float[][] correlations = new float[lagCount][n];
    int used = 0;
    float[] diff = new float[n - 1];
    for (int fixed = lo; fixed < hi; fixed++) {
      // First differences remove smooth shading; what remains is texture.
      double energy = 0.0;
      float prev = rows ? gray[fixed * SIZE + lo] : gray[lo * SIZE + fixed];
      for (int i = 1; i < n; i++) {
        float value = rows ? gray[fixed * SIZE + lo + i] : gray[(lo + i) * SIZE + fixed];
        diff[i - 1] = value - prev;
        prev = value;
        energy += diff[i - 1] * diff[i - 1];
      }
      if (energy < 6.0) continue; // too flat to judge (e.g. plain skin)
      for (int lag = minLag; lag <= maxLag; lag++) {
        double correlation = 0.0;
        for (int i = 0; i + lag < diff.length; i++) {
          correlation += diff[i] * diff[i + lag];
        }
        correlations[lag - minLag][used] = (float) (correlation / energy);
      }
      used++;
    }
    if (used < 8) return 0f;
    float peak = 0f;
    for (int lag = 0; lag < lagCount; lag++) {
      float[] values = java.util.Arrays.copyOf(correlations[lag], used);
      java.util.Arrays.sort(values);
      // Absolute value of the median: a lag near half the grid period is
      // consistently negative, which is equally characteristic.
      float median = Math.abs(values[used / 2]);
      if (median > peak) peak = median;
    }
    return peak;
  }

  private static float clamp01(float value) {
    return Math.max(0f, Math.min(1f, value));
  }
}
