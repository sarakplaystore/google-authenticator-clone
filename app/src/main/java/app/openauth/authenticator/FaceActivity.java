package app.openauth.authenticator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enrols a face and checks a face against the enrolment, using the front camera only.
 *
 * <p>The comparison runs on this device, the frames are never stored and never leave the
 * phone. The Android face recognition of the system is not used at all.
 */
@SuppressWarnings("deprecation")
public final class FaceActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
  /** Extra that selects what the activity does. */
  public static final String EXTRA_MODE = "app.openauth.authenticator.FACE_MODE";
  /** Enrol a new face. */
  public static final int MODE_ENROLL = 1;
  /** Check the face of the enrolled person. */
  public static final int MODE_VERIFY = 2;
  /** Set when the user turned the face lock off while the app was locked. */
  public static final String EXTRA_LOCK_DISABLED = "app.openauth.authenticator.FACE_LOCK_DISABLED";
  private static final int REQUEST_CAMERA = 71;
  private static final int REQUEST_CREDENTIAL = 72;
  private static final int REQUIRED_SAMPLES = 5;
  private static final long SAMPLE_INTERVAL_MS = 1100L;
  private static final long ENROLL_WARMUP_MS = 1800L;
  private static final long VERIFY_INTERVAL_MS = 300L;
  private static final long OPTIONS_AFTER_MS = 8000L;
  private static final float REGION_FRACTION = 0.8f;

  private SurfaceView previewView;
  private SurfaceHolder surfaceHolder;
  private Camera camera;
  private int previewWidth;
  private int previewHeight;
  private int displayOrientation;
  private boolean surfaceReady;
  private volatile boolean finished;
  private int mode = MODE_VERIFY;
  private FaceStore store;
  private float[][] samples;
  private final List<float[]> enrolled = new ArrayList<float[]>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean processing = new AtomicBoolean(false);

  private GuideOverlay overlay;
  private TextView instructionView;
  private TextView statusView;
  private LinearLayout actionBar;
  private long lastSampleAt;
  private long startedAt;
  private int consecutiveMatches;
  private int failures;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    mode = getIntent().getIntExtra(EXTRA_MODE, MODE_VERIFY);
    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(0);

    store = new FaceStore(this);
    if (mode == MODE_VERIFY) {
      samples = store.loadSamples();
      if (samples == null) {
        Toast.makeText(this, "No face is enrolled on this device", Toast.LENGTH_SHORT).show();
        setResult(RESULT_CANCELED);
        finish();
        return;
      }
    }

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    previewView = new SurfaceView(this);
    surfaceHolder = previewView.getHolder();
    surfaceHolder.addCallback(this);
    root.addView(previewView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    overlay = new GuideOverlay(this);
    root.addView(overlay, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    LinearLayout topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(8), dp(8), dp(16), dp(8));
    topBar.setBackgroundColor(0x99000000);
    TextView back = new TextView(this);
    back.setText("‹");
    back.setTextSize(36);
    back.setTextColor(Color.WHITE);
    back.setGravity(Gravity.CENTER);
    topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    back.setContentDescription("Close");
    back.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { cancel(); }
    });
    TextView title = new TextView(this);
    title.setText(mode == MODE_ENROLL ? "Set up face unlock" : "Unlock");
    title.setTextSize(18);
    title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    title.setTextColor(Color.WHITE);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    titleParams.leftMargin = dp(8);
    topBar.addView(title, titleParams);
    FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
    root.addView(topBar, topParams);

    LinearLayout bottom = new LinearLayout(this);
    bottom.setOrientation(LinearLayout.VERTICAL);
    bottom.setGravity(Gravity.CENTER);
    bottom.setPadding(dp(24), dp(16), dp(24), dp(22));
    bottom.setBackgroundColor(0x99000000);
    instructionView = new TextView(this);
    instructionView.setText(mode == MODE_ENROLL
        ? "Centre your face in the frame" : "Look at the camera to unlock");
    instructionView.setTextColor(Color.WHITE);
    instructionView.setTextSize(16);
    instructionView.setGravity(Gravity.CENTER);
    bottom.addView(instructionView);
    statusView = new TextView(this);
    statusView.setText(mode == MODE_ENROLL ? "Sample 0 of " + REQUIRED_SAMPLES : "Looking for you");
    statusView.setTextColor(0xFFDADCE0);
    statusView.setTextSize(13);
    statusView.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    statusParams.topMargin = dp(6);
    bottom.addView(statusView, statusParams);

    actionBar = new LinearLayout(this);
    actionBar.setOrientation(LinearLayout.HORIZONTAL);
    actionBar.setGravity(Gravity.CENTER);
    actionBar.setVisibility(View.GONE);
    TextView usePin = actionButton("Use device PIN");
    usePin.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { confirmDeviceCredential(); }
    });
    actionBar.addView(usePin, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView disable = actionButton("Turn off face unlock");
    disable.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { confirmDisable(); }
    });
    LinearLayout.LayoutParams disableParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    disableParams.leftMargin = dp(12);
    actionBar.addView(disable, disableParams);
    LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    actionParams.topMargin = dp(12);
    bottom.addView(actionBar, actionParams);

    FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    root.addView(bottom, bottomParams);
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        int topInset = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetTop() : 0;
        int bottomInset = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetBottom() : 0;
        topBar.setPadding(dp(8), dp(8) + topInset, dp(16), dp(8));
        bottom.setPadding(dp(24), dp(16), dp(24), dp(22) + bottomInset);
        return insets;
      }
    });
    setContentView(root);
    startedAt = System.currentTimeMillis();

    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraIfReady();
    } else if (mode == MODE_ENROLL) {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    } else {
      showStatus("Camera access is needed to check your face", 0.0);
      showOptions();
    }
  }

  private TextView actionButton(String label) {
    TextView view = new TextView(this);
    view.setText(label);
    view.setTextColor(Color.WHITE);
    view.setTextSize(14);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(14), dp(8), dp(14), dp(8));
    view.setBackground(roundDrawable(0x33FFFFFF, dp(18)));
    view.setClickable(true);
    view.setFocusable(true);
    return view;
  }

  @Override protected void onResume() {
    super.onResume();
    if (!finished && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraIfReady();
    }
  }

  @Override protected void onPause() {
    releaseCamera();
    super.onPause();
  }

  @Override protected void onDestroy() {
    finished = true;
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    startCameraIfReady();
  }

  @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (camera != null) {
      try {
        camera.stopPreview();
        camera.setPreviewDisplay(holder);
        camera.setPreviewCallback(this);
        camera.startPreview();
      } catch (Exception e) {
        showCameraError();
      }
    }
  }

  @Override public void surfaceDestroyed(SurfaceHolder holder) {
    surfaceReady = false;
    releaseCamera();
  }

  @Override public void onPreviewFrame(final byte[] data, Camera source) {
    if (data == null || finished || !processing.compareAndSet(false, true)) return;
    final byte[] frame = data.clone();
    final int width = previewWidth;
    final int height = previewHeight;
    final long now = System.currentTimeMillis();
    final long interval = mode == MODE_ENROLL ? SAMPLE_INTERVAL_MS : VERIFY_INTERVAL_MS;
    if (now - lastSampleAt < interval) {
      processing.set(false);
      return;
    }
    lastSampleAt = now;
    try {
      worker.execute(new Runnable() {
        @Override public void run() {
          try {
            if (finished) return;
            handleFrame(frame, width, height);
          } catch (RuntimeException ignored) {
            // A partial frame is simply skipped, the camera delivers the next one shortly.
          } finally {
            processing.set(false);
          }
        }
      });
    } catch (RuntimeException ignored) {
      processing.set(false);
    }
  }

  /** Builds the descriptor of a frame and either enrols it or compares it. */
  private void handleFrame(byte[] frame, int width, int height) {
    int side = (int) (Math.min(width, height) * REGION_FRACTION);
    int left = (width - side) / 2;
    int top = (height - side) / 2;
    if (mode == MODE_ENROLL && System.currentTimeMillis() - startedAt < ENROLL_WARMUP_MS) {
      return;
    }
    float[] raw = FaceTemplate.downsample(frame, width, height, left, top,
        left + side, top + side);
    double quality = FaceTemplate.quality(raw);
    if (quality < 2.0) {
      showStatus("Move into the frame", 0.0);
      if (mode == MODE_VERIFY && System.currentTimeMillis() - startedAt > OPTIONS_AFTER_MS) {
        showOptions();
      }
      return;
    }
    float[] grid = FaceTemplate.normalize(raw);
    if (mode == MODE_ENROLL) {
      enrol(grid);
    } else {
      verify(grid);
    }
  }

  /** Stores one enrolment sample. */
  private void enrol(float[] grid) {
    synchronized (enrolled) {
      enrolled.add(FaceTemplate.describe(grid));
      final int count = enrolled.size();
      if (count < REQUIRED_SAMPLES) {
        showStatus("Sample " + count + " of " + REQUIRED_SAMPLES, 0.0);
        return;
      }
      float[][] samples = new float[count + 1][];
      for (int i = 0; i < count; i++) samples[i] = enrolled.get(i);
      samples[count] = FaceTemplate.average(samples);
      try {
        store.saveSamples(samples);
        store.setEnabled(true);
      } catch (RuntimeException e) {
        Toast.makeText(this, "The face data could not be saved", Toast.LENGTH_LONG).show();
        cancel();
        return;
      }
      showStatus("Face saved", 1.0);
      succeed();
    }
  }

  /** Compares a capture with the enrolled samples. */
  private void verify(float[] grid) {
    double score = FaceTemplate.matchScore(samples, grid);
    if (score >= FaceThreshold.VALUE) {
      consecutiveMatches++;
      if (consecutiveMatches >= FaceThreshold.REQUIRED_MATCHES) {
        showStatus("Face recognized", 1.0);
        succeed();
        return;
      }
      showStatus("Hold still…", score);
    } else {
      consecutiveMatches = 0;
      failures++;
      if (System.currentTimeMillis() - startedAt > OPTIONS_AFTER_MS) {
        showOptions();
      }
      showStatus("Not recognized, try again", score);
    }
  }

  private void showStatus(final String text, final double score) {
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        statusView.setText(text);
        if (overlay != null) {
          overlay.setScore(score);
          overlay.invalidate();
        }
      }
    });
  }

  private void showOptions() {
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        actionBar.setVisibility(View.VISIBLE);
        if (mode == MODE_VERIFY) {
          instructionView.setText("Face unlock did not succeed");
        }
      }
    });
  }

  private void succeed() {
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        finished = true;
        releaseCamera();
        setResult(RESULT_OK);
        finish();
      }
    });
  }

  private void cancel() {
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        finished = true;
        releaseCamera();
        setResult(RESULT_CANCELED);
        finish();
      }
    });
  }

  /** Falls back to the PIN, pattern or password of the device. */
  private void confirmDeviceCredential() {
    KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
    if (keyguard == null) {
      showStatus("This device has no screen lock", 0.0);
      return;
    }
    Intent intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Authenticator",
        "Confirm your device PIN, pattern or password");
    if (intent == null) {
      showStatus("Set up a screen lock to use this option", 0.0);
      return;
    }
    try {
      startActivityForResult(intent, REQUEST_CREDENTIAL);
    } catch (RuntimeException e) {
      showStatus("This device has no screen lock", 0.0);
    }
  }

  /** Turns the face lock off after the device credential was confirmed. */
  private void confirmDisable() {
    KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
    if (keyguard == null) {
      return;
    }
    Intent intent = keyguard.createConfirmDeviceCredentialIntent("Turn off face unlock",
        "Confirm your device PIN, pattern or password");
    if (intent == null) {
      new AlertDialog.Builder(this)
          .setTitle("Turn off face unlock?")
          .setMessage("The app will no longer ask for your face. The enrolled face data is "
              + "deleted from this device.")
          .setNegativeButton("Cancel", null)
          .setPositiveButton("Turn off", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface dialog, int which) {
              disableLock();
            }
          })
          .show();
      return;
    }
    try {
      startActivityForResult(intent, REQUEST_CREDENTIAL);
    } catch (RuntimeException ignored) {
      // No secure credential available, the dialog above is used instead.
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_CREDENTIAL) {
      if (resultCode == RESULT_OK) {
        succeed();
      } else if (mode == MODE_VERIFY && failures > 0) {
        new AlertDialog.Builder(this)
            .setTitle("Turn off face unlock?")
            .setMessage("The app will no longer ask for your face. The enrolled face data is "
                + "deleted from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Turn off", new android.content.DialogInterface.OnClickListener() {
              @Override public void onClick(android.content.DialogInterface dialog, int which) {
                disableLock();
              }
            })
            .show();
      }
    }
  }

  private void disableLock() {
    store.clear();
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        finished = true;
        releaseCamera();
        Intent result = new Intent();
        result.putExtra(EXTRA_LOCK_DISABLED, true);
        setResult(RESULT_CANCELED, result);
        finish();
      }
    });
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQUEST_CAMERA) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        startCameraIfReady();
      } else if (mode == MODE_ENROLL) {
        Toast.makeText(this, "Camera access is needed to enrol a face", Toast.LENGTH_LONG).show();
        cancel();
      } else {
        showStatus("Camera access is needed to check your face", 0.0);
        showOptions();
      }
    }
  }

  private void startCameraIfReady() {
    if (!surfaceReady || camera != null || finished
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    try {
      int id = findFrontCamera();
      if (id < 0) {
        showCameraError();
        return;
      }
      camera = Camera.open(id);
      Camera.Parameters parameters = camera.getParameters();
      Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
      if (size != null) {
        parameters.setPreviewSize(size.width, size.height);
        previewWidth = size.width;
        previewHeight = size.height;
      }
      parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
      List<String> focusModes = parameters.getSupportedFocusModes();
      if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      }
      camera.setParameters(parameters);
      displayOrientation = cameraOrientation(id);
      camera.setDisplayOrientation(displayOrientation);
      camera.setPreviewDisplay(surfaceHolder);
      camera.setPreviewCallback(this);
      camera.startPreview();
      if (overlay != null) {
        overlay.setRegion(previewWidth, previewHeight, displayOrientation);
        overlay.invalidate();
      }
    } catch (Exception e) {
      releaseCamera();
      showCameraError();
    }
  }

  private int findFrontCamera() {
    int count = Camera.getNumberOfCameras();
    Camera.CameraInfo info = new Camera.CameraInfo();
    for (int i = 0; i < count; i++) {
      Camera.getCameraInfo(i, info);
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
    }
    return -1;
  }

  private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
    if (sizes == null || sizes.isEmpty()) return null;
    Camera.Size best = null;
    double bestScore = Double.MAX_VALUE;
    for (Camera.Size size : sizes) {
      double ratio = size.width / (double) size.height;
      double ratioError = Math.abs(ratio - (4.0 / 3.0));
      double resolutionPenalty = size.width * (double) size.height > 800.0 * 600.0 ? 2.0 : 0.0;
      double score = ratioError * 100.0 + resolutionPenalty
          + Math.abs(size.width - 640) / 1000.0;
      if (score < bestScore) {
        bestScore = score;
        best = size;
      }
    }
    return best;
  }

  private int cameraOrientation(int cameraId) {
    Camera.CameraInfo info = new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);
    int rotation = getWindowManager().getDefaultDisplay().getRotation();
    int degrees = rotation == android.view.Surface.ROTATION_90 ? 90
        : rotation == android.view.Surface.ROTATION_180 ? 180
        : rotation == android.view.Surface.ROTATION_270 ? 270 : 0;
    int result;
    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      result = (info.orientation + degrees) % 360;
      result = (360 - result) % 360;
    } else {
      result = (info.orientation - degrees + 360) % 360;
    }
    return result;
  }

  private void showCameraError() {
    if (mode == MODE_VERIFY) {
      showStatus("The front camera could not be opened", 0.0);
      showOptions();
      return;
    }
    Toast.makeText(this, "The front camera could not be opened", Toast.LENGTH_LONG).show();
    cancel();
  }

  private synchronized void releaseCamera() {
    if (camera == null) return;
    try {
      camera.setPreviewCallback(null);
      camera.stopPreview();
    } catch (RuntimeException ignored) {}
    try {
      camera.release();
    } catch (RuntimeException ignored) {}
    camera = null;
  }

  private android.graphics.drawable.GradientDrawable roundDrawable(int color, int radius) {
    android.graphics.drawable.GradientDrawable drawable =
        new android.graphics.drawable.GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(radius);
    return drawable;
  }

  private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  /** Draws the guide that shows where the face has to be, plus the match meter. */
  private final class GuideOverlay extends View {
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private int previewWidth;
    private int previewHeight;
    private int displayOrientation;
    private double score;

    GuideOverlay(Activity activity) {
      super(activity);
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      dim.setColor(0x66000000);
      clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      border.setColor(Color.WHITE);
      border.setStyle(Paint.Style.STROKE);
      border.setStrokeWidth(dp(2));
      border.setStrokeCap(Paint.Cap.ROUND);
      track.setColor(0x55FFFFFF);
      fill.setColor(Color.rgb(26, 115, 232));
    }

    void setRegion(int width, int height, int orientation) {
      previewWidth = width;
      previewHeight = height;
      displayOrientation = orientation;
    }

    void setScore(double value) {
      score = value;
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (previewWidth <= 0 || previewHeight <= 0) return;
      boolean swapped = displayOrientation == 90 || displayOrientation == 270;
      int rotatedWidth = swapped ? previewHeight : previewWidth;
      int rotatedHeight = swapped ? previewWidth : previewHeight;
      float side = Math.min(previewWidth, previewHeight) * REGION_FRACTION;
      float scaleX = getWidth() / (float) rotatedWidth;
      float scaleY = getHeight() / (float) rotatedHeight;
      float halfWidth = side * 0.5f * scaleX;
      float halfHeight = side * 0.5f * scaleY;
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      frame.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);

      int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
      canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
      canvas.drawRoundRect(frame, dp(28), dp(28), clear);
      canvas.restoreToCount(save);
      canvas.drawRoundRect(frame, dp(28), dp(28), border);

      float meterWidth = Math.min(frame.width() * 0.7f, dp(220));
      float meterHeight = dp(6);
      float meterLeft = cx - meterWidth / 2f;
      float meterTop = frame.bottom - dp(36);
      canvas.drawRoundRect(new RectF(meterLeft, meterTop, meterLeft + meterWidth,
          meterTop + meterHeight), meterHeight / 2f, meterHeight / 2f, track);
      double threshold = FaceThreshold.VALUE;
      double fraction = (score - 0.5) / Math.max(0.01, threshold - 0.5);
      fraction = Math.max(0.0, Math.min(1.0, fraction));
      if (fraction > 0.0) {
        fill.setColor(score >= threshold ? Color.rgb(52, 168, 104) : Color.rgb(26, 115, 232));
        canvas.drawRoundRect(new RectF(meterLeft, meterTop, meterLeft + meterWidth * (float) fraction,
            meterTop + meterHeight), meterHeight / 2f, meterHeight / 2f, fill);
      }
    }
  }
}
