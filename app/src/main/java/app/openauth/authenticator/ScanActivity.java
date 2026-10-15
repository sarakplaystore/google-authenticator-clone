package app.openauth.authenticator;

import android.Manifest;
import android.app.Activity;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.ReaderException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera-based QR importer for standard otpauth://totp codes. */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
  public static final String EXTRA_RESULT = "app.openauth.authenticator.OTP_URI";
  private static final int REQUEST_CAMERA = 52;

  private SurfaceView previewView;
  private SurfaceHolder surfaceHolder;
  private Camera camera;
  private int previewWidth;
  private int previewHeight;
  private boolean surfaceReady;
  private volatile boolean finished;
  private final AtomicBoolean decoding = new AtomicBoolean(false);
  private final ExecutorService decoder = Executors.newSingleThreadExecutor();
  private final MultiFormatReader reader = new MultiFormatReader();

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(0);

    Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    reader.setHints(hints);

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    previewView = new SurfaceView(this);
    surfaceHolder = previewView.getHolder();
    surfaceHolder.addCallback(this);
    root.addView(previewView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    ViewfinderOverlay overlay = new ViewfinderOverlay(this);
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
    back.setContentDescription("Close scanner");
    back.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { finish(); }
    });
    TextView title = new TextView(this);
    title.setText("Scan QR code");
    title.setTextSize(18);
    title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    title.setTextColor(Color.WHITE);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
    titleParams.leftMargin = dp(8);
    topBar.addView(title, titleParams);
    FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
    root.addView(topBar, topParams);

    LinearLayout bottom = new LinearLayout(this);
    bottom.setOrientation(LinearLayout.VERTICAL);
    bottom.setGravity(Gravity.CENTER);
    bottom.setPadding(dp(24), dp(18), dp(24), dp(26));
    bottom.setBackgroundColor(0x99000000);
    TextView instruction = new TextView(this);
    instruction.setText("Place the QR code inside the frame");
    instruction.setTextColor(Color.WHITE);
    instruction.setTextSize(16);
    instruction.setGravity(Gravity.CENTER);
    instruction.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    bottom.addView(instruction);
    TextView note = new TextView(this);
    note.setText("Only standard time-based authenticator codes are supported");
    note.setTextColor(0xFFDADCE0);
    note.setTextSize(13);
    note.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
    noteParams.topMargin = dp(8);
    bottom.addView(note, noteParams);
    FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    root.addView(bottom, bottomParams);
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        int topInset = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetTop() : 0;
        int bottomInset = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetBottom() : 0;
        topBar.setPadding(dp(8), dp(8) + topInset, dp(16), dp(8));
        bottom.setPadding(dp(24), dp(18), dp(24), dp(26) + bottomInset);
        return insets;
      }
    });
    setContentView(root);

    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraIfReady();
    } else {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }
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
    decoder.shutdownNow();
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
    if (data == null || finished || !decoding.compareAndSet(false, true)) return;
    final byte[] frame = data.clone();
    final int width = previewWidth;
    final int height = previewHeight;
    decoder.execute(new Runnable() {
      @Override public void run() {
        try {
          PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
              frame, width, height, 0, 0, width, height, false);
          try {
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance)));
            acceptResult(result);
          } catch (ReaderException firstAttempt) {
            try {
              if (luminance.isRotateSupported()) {
                LuminanceSource rotated = luminance.rotateCounterClockwise();
                Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(rotated)));
                acceptResult(result);
              }
            } catch (ReaderException ignored) {
              // Most camera frames contain no code. Continue with the next preview frame.
            }
          }
        } catch (RuntimeException ignored) {
          // Ignore incomplete preview frames; the camera will provide another shortly.
        } finally {
          reader.reset();
          decoding.set(false);
        }
      }
    });
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQUEST_CAMERA) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        startCameraIfReady();
      } else {
        Toast.makeText(this, "Camera permission is needed to scan a QR code. You can enter a setup key instead.",
            Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
      }
    }
  }

  private void startCameraIfReady() {
    if (!surfaceReady || camera != null || finished
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    try {
      int id = findBackCamera();
      camera = id >= 0 ? Camera.open(id) : Camera.open();
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
      } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      }
      camera.setParameters(parameters);
      setCameraOrientation(id);
      camera.setPreviewDisplay(surfaceHolder);
      camera.setPreviewCallback(this);
      camera.startPreview();
    } catch (Exception e) {
      releaseCamera();
      showCameraError();
    }
  }

  private int findBackCamera() {
    int count = Camera.getNumberOfCameras();
    Camera.CameraInfo info = new Camera.CameraInfo();
    for (int i = 0; i < count; i++) {
      Camera.getCameraInfo(i, info);
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
    }
    return count == 0 ? -1 : 0;
  }

  private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
    if (sizes == null || sizes.isEmpty()) return null;
    Camera.Size best = sizes.get(0);
    double bestScore = Double.MAX_VALUE;
    for (Camera.Size size : sizes) {
      double ratio = size.width / (double) size.height;
      double ratioError = Math.abs(ratio - (16.0 / 9.0));
      double areaPenalty = size.width * (double) size.height > 1920.0 * 1080.0 ? 1.0 : 0.0;
      double score = ratioError * 100.0 + areaPenalty + Math.abs(size.width - 1280) / 10000.0;
      if (score < bestScore) {
        best = size;
        bestScore = score;
      }
    }
    return best;
  }

  private void setCameraOrientation(int cameraId) {
    if (cameraId < 0) return;
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
    camera.setDisplayOrientation(result);
  }

  private void acceptResult(Result result) {
    if (result == null || result.getText() == null) return;
    String text = result.getText();
    if (text.toLowerCase(Locale.US).startsWith("otpauth://")) {
      complete(text);
    }
  }

  private void complete(final String text) {
    if (text == null || finished) return;
    runOnUiThread(new Runnable() {
      @Override public void run() {
        if (finished) return;
        finished = true;
        releaseCamera();
        Intent result = new Intent();
        result.putExtra(EXTRA_RESULT, text);
        setResult(RESULT_OK, result);
        finish();
      }
    });
  }

  private void showCameraError() {
    Toast.makeText(this, "Could not open the camera. Enter the setup key manually instead.",
        Toast.LENGTH_LONG).show();
    setResult(RESULT_CANCELED);
    finish();
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

  private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  private final class ViewfinderOverlay extends View {
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();

    ViewfinderOverlay(Activity activity) {
      super(activity);
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      dim.setColor(0x5F000000);
      clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      border.setColor(Color.WHITE);
      border.setStyle(Paint.Style.STROKE);
      border.setStrokeWidth(dp(2));
      border.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float side = Math.min(getWidth() * 0.72f, dp(300));
      float left = (getWidth() - side) / 2f;
      float top = (getHeight() - side) / 2f;
      frame.set(left, top, left + side, top + side);
      int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
      canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
      canvas.drawRoundRect(frame, dp(22), dp(22), clear);
      canvas.restoreToCount(save);
      canvas.drawRoundRect(frame, dp(22), dp(22), border);
      drawCorners(canvas, left, top, side);
    }

    private void drawCorners(Canvas canvas, float left, float top, float side) {
      Paint corner = border;
      corner.setStrokeWidth(dp(5));
      float length = dp(22);
      float right = left + side;
      float bottom = top + side;
      canvas.drawLine(left + dp(3), top + length, left + dp(3), top + dp(3), corner);
      canvas.drawLine(left + dp(3), top + dp(3), left + length, top + dp(3), corner);
      canvas.drawLine(right - length, top + dp(3), right - dp(3), top + dp(3), corner);
      canvas.drawLine(right - dp(3), top + dp(3), right - dp(3), top + length, corner);
      canvas.drawLine(left + dp(3), bottom - length, left + dp(3), bottom - dp(3), corner);
      canvas.drawLine(left + dp(3), bottom - dp(3), left + length, bottom - dp(3), corner);
      canvas.drawLine(right - length, bottom - dp(3), right - dp(3), bottom - dp(3), corner);
      canvas.drawLine(right - dp(3), bottom - dp(3), right - dp(3), bottom - length, corner);
      corner.setStrokeWidth(dp(2));
    }
  }
}
