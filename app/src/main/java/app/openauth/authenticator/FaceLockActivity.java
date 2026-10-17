package app.openauth.authenticator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom, on-device face registration and face unlock with liveness.
 *
 * <p>The identity descriptor alone can be matched by a good photograph, so a
 * scan is never just a match: {@link LivenessSession} requires three
 * randomised live challenges (blink / turn / nod) performed by the enrolled
 * face, verifies 3D parallax during turns, flashes the screen at random
 * times and checks the face responds to the light, and rejects frames with
 * the moiré grid of a replayed display. The platform
 * {@link android.media.FaceDetector} provides native face presence on top of
 * the app's own landmark geometry. Failed attempts are throttled like PIN
 * attempts. The same check guards every code reveal ({@link #MODE_REVEAL}).
 * A six-digit backup PIN is created during setup so a failed scan cannot
 * lock the user out permanently.</p>
 *
 * <p>The layout is fully edge-to-edge and measured at runtime: the camera
 * preview is centre-cropped to the display (never stretched), the face guide
 * sits in the free space between the top bar and the bottom panel, and
 * insets for the status bar, gesture bar and display cutout are honoured on
 * every Android version.</p>
 */
@SuppressWarnings("deprecation")
public final class FaceLockActivity extends Activity
    implements SurfaceHolder.Callback, Camera.PreviewCallback {
  public static final String EXTRA_MODE = "app.openauth.authenticator.FACE_MODE";
  public static final String EXTRA_ACCOUNT_LABEL =
      "app.openauth.authenticator.FACE_ACCOUNT_LABEL";
  public static final String MODE_ENROLL = "enroll";
  public static final String MODE_UNLOCK = "unlock";
  /** A fresh face check before a hidden verification code is revealed. */
  public static final String MODE_REVEAL = "reveal";

  private enum OverlayMode { ACTIVE, SUCCESS, ERROR }

  // Cue arrows point where the nose should move in the mirrored preview:
  // the user's own left appears on the right side of a selfie preview.
  private enum OverlayCue { NONE, CENTER, TURN_USER_LEFT, TURN_USER_RIGHT, NOD, BLINK }

  private static final int REQUEST_CAMERA = 63;
  private static final int COLOR_BACKGROUND = Color.rgb(9, 12, 16);
  private static final int COLOR_PANEL = Color.rgb(24, 29, 36);
  private static final int COLOR_TEXT = Color.rgb(244, 247, 250);
  private static final int COLOR_SECONDARY = Color.rgb(174, 184, 196);
  private static final int COLOR_PRIMARY = Color.rgb(138, 180, 248);
  /** Analyse roughly every 110 ms: fast enough to catch a blink. */
  private static final long FRAME_INTERVAL_MS = 110L;
  private static final int DETECTOR_WIDTH = 320;

  private final Handler ui = new Handler();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean processing = new AtomicBoolean(false);
  private final List<FaceTemplate> captured = new ArrayList<FaceTemplate>();
  private final SecureRandom sessionRandom = new SecureRandom();

  private boolean enrollMode;
  private boolean revealMode;
  private String accountLabel;
  private boolean finished;
  private boolean cameraStarted;
  private boolean cameraFailed;
  private boolean surfaceReady;
  private boolean showPermissionPanel;
  private long lastFrameAt;
  private int frameCounter;
  private int previewWidth;
  private int previewHeight;
  private int sensorRotation;
  private int cameraId = -1;
  private float previewAspect = 9f / 16f;
  private volatile boolean flashLit;

  private FaceStore faceStore;
  private List<FaceTemplate> enrolled = new ArrayList<FaceTemplate>();
  private Camera camera;
  private SurfaceHolder surfaceHolder;
  private LivenessSession session;
  private int lastChallengeShown = -1;
  private int lastBlockedSecond = -1;

  // Native platform face detection (presence only; liveness is ours).
  private FaceDetector faceDetector;
  private int detectorBitmapWidth;
  private int detectorBitmapHeight;
  private boolean detectorBroken;
  private boolean detectorOk;
  private boolean detectorMulti;
  private long lastDetectorAtMs;
  private float lastMeanLuma = -1f;

  // Enrolment auto-capture state.
  private int enrollStep;
  private int enrollHoldFrames;
  private long enrollStepStartedAtMs;
  private FaceSignals enrollBaseSignals;
  private boolean manualCaptureRequested;
  private int blinkBaseFrames;
  private float blinkBaseOpennessSum;
  private float blinkBaseOpenness = -1f;
  private long blinkDipAtMs = -1L;

  private FrameLayout rootView;
  private SurfaceView previewView;
  private View flashView;
  private FaceOverlay overlay;
  private View topBarView;
  private ScrollView panelScroll;
  private TextView titleView;
  private TextView stepView;
  private TextView statusView;
  private TextView instructionView;
  private TextView primaryButton;
  private TextView secondaryButton;
  private TextView auxiliaryButton;
  private TextView captureProgress;
  private boolean compactLayout;

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      if (finished) return;
      updateScreenLight();
      tickBlockedCountdown();
      tickEnrollBlinkTimeout();
      ui.postDelayed(this, 120L);
    }
  };

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    String mode = getIntent().getStringExtra(EXTRA_MODE);
    enrollMode = MODE_ENROLL.equals(mode);
    revealMode = MODE_REVEAL.equals(mode);
    accountLabel = getIntent().getStringExtra(EXTRA_ACCOUNT_LABEL);
    faceStore = new FaceStore(this);
    if (!enrollMode) {
      try {
        enrolled = faceStore.loadTemplates();
      } catch (RuntimeException e) {
        enrolled = new ArrayList<FaceTemplate>();
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      }
    }
    compactLayout = getResources().getConfiguration().screenHeightDp < 560
        || getResources().getConfiguration().fontScale > 1.25f;

    configureWindow();
    setContentView(buildScreen());
    applyInitialUi();
    ui.post(ticker);
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraIfReady();
    } else {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }
  }

  @Override protected void onResume() {
    super.onResume();
    ui.removeCallbacks(ticker);
    ui.post(ticker);
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      if (showPermissionPanel) {
        showPermissionPanel = false;
        applyInitialUi();
      }
      if (!cameraFailed) {
        startCameraIfReady();
      }
    } else if (!showPermissionPanel) {
      showCameraPermissionPanel();
    }
  }

  @Override protected void onPause() {
    ui.removeCallbacks(ticker);
    releaseCamera();
    restoreScreenLight();
    super.onPause();
  }

  @Override protected void onStop() {
    super.onStop();
    if (!isFinishing() && !isChangingConfigurations() && FaceGate.unlocked) {
      // An in-progress face update must not leave the main screen unlocked
      // when the user leaves the app from here.
      FaceGate.unlocked = false;
      FaceGate.promptOnNextResume = true;
    }
  }

  @Override protected void onDestroy() {
    finished = true;
    ui.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    releaseCamera();
    super.onDestroy();
  }

  @Override public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    compactLayout = getResources().getConfiguration().screenHeightDp < 560
        || getResources().getConfiguration().fontScale > 1.25f;
    if (camera != null) {
      try {
        camera.stopPreview();
        applyCameraOrientation();
        camera.setPreviewDisplay(surfaceHolder);
        camera.setPreviewCallback(this);
        camera.startPreview();
      } catch (Exception e) {
        failCamera();
      }
    }
  }

  @Override public void onBackPressed() {
    if (enrollMode && !finished) {
      new AlertDialog.Builder(this)
          .setTitle("Cancel face registration?")
          .setMessage("Face registration is required to open Authenticator.")
          .setNegativeButton("Keep registering", null)
          .setPositiveButton("Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
              finishCanceled();
            }
          })
          .show();
    } else {
      finishCanceled();
    }
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode != REQUEST_CAMERA) return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
      showPermissionPanel = false;
      applyInitialUi();
      startCameraIfReady();
    } else {
      showCameraPermissionPanel();
    }
  }

  // ---------------------------------------------------------- window & layout

  private void configureWindow() {
    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_SECURE);
    if (Build.VERSION.SDK_INT >= 30) {
      window.setDecorFitsSystemWindows(false);
    }
    window.setStatusBarColor(Color.TRANSPARENT);
    window.setNavigationBarColor(Color.TRANSPARENT);
    window.getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams params = window.getAttributes();
      params.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      window.setAttributes(params);
    }
  }

  private View buildScreen() {
    FrameLayout root = new FrameLayout(this);
    rootView = root;
    root.setBackgroundColor(COLOR_BACKGROUND);

    previewView = new SurfaceView(this);
    surfaceHolder = previewView.getHolder();
    surfaceHolder.addCallback(this);
    root.addView(previewView, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER));

    // Randomised screen flash for the light-response liveness check; also
    // used at low alpha as a fill light in the dark, like system face unlock.
    flashView = new View(this);
    flashView.setBackgroundColor(Color.WHITE);
    flashView.setAlpha(0f);
    flashView.setClickable(false);
    root.addView(flashView, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    overlay = new FaceOverlay(this);
    root.addView(overlay, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout topBar = new LinearLayout(this);
    topBarView = topBar;
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(6), dp(6), dp(18), dp(6));
    topBar.setBackgroundColor(0x59000000);
    TextView close = text("✕", 20, Color.WHITE, Typeface.NORMAL);
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Cancel");
    close.setClickable(true);
    close.setFocusable(true);
    close.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { onBackPressed(); }
    });
    topBar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
    titleView = text("", 17, Color.WHITE, Typeface.BOLD);
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    titleParams.leftMargin = dp(4);
    topBar.addView(titleView, titleParams);
    root.addView(topBar, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP));

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    int padH = compactLayout ? 18 : 22;
    int padV = compactLayout ? 13 : 18;
    panel.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
    GradientDrawable panelBackground = new GradientDrawable();
    panelBackground.setColor(COLOR_PANEL);
    panelBackground.setCornerRadius(dp(24));
    panel.setBackground(panelBackground);
    panel.setElevation(dp(12));

    stepView = text("", 11.5f, COLOR_PRIMARY, Typeface.BOLD);
    stepView.setLetterSpacing(0.12f);
    stepView.setSingleLine(true);
    panel.addView(stepView);

    statusView = text("", compactLayout ? 20 : 23, COLOR_TEXT, Typeface.BOLD);
    statusView.setMaxLines(2);
    statusView.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    statusParams.topMargin = dp(4);
    panel.addView(statusView, statusParams);

    instructionView = text("", 14, COLOR_SECONDARY, Typeface.NORMAL);
    instructionView.setLineSpacing(dp(2), 1f);
    instructionView.setMaxLines(3);
    instructionView.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    instructionParams.topMargin = dp(5);
    panel.addView(instructionView, instructionParams);

    captureProgress = text("", 11, COLOR_SECONDARY, Typeface.NORMAL);
    captureProgress.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    progressParams.topMargin = dp(9);
    panel.addView(captureProgress, progressParams);

    primaryButton = pillButton(true);
    LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(compactLayout ? 48 : 52));
    primaryParams.topMargin = dp(11);
    panel.addView(primaryButton, primaryParams);

    secondaryButton = pillButton(false);
    LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    secondaryParams.topMargin = dp(8);
    panel.addView(secondaryButton, secondaryParams);

    auxiliaryButton = text("", 13.5f, COLOR_PRIMARY, Typeface.BOLD);
    auxiliaryButton.setGravity(Gravity.CENTER);
    auxiliaryButton.setPadding(dp(6), dp(11), dp(6), dp(5));
    auxiliaryButton.setClickable(true);
    auxiliaryButton.setFocusable(true);
    panel.addView(auxiliaryButton, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    TextView deviceNote = text(revealMode
            ? "Live face check required · recognition stays on this device"
            : "On-device face recognition with a live check · no fingerprint or Touch ID",
        10.5f, 0xFF8A94A3, Typeface.NORMAL);
    deviceNote.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    noteParams.topMargin = dp(6);
    panel.addView(deviceNote, noteParams);

    // The panel scrolls internally on short windows (split screen, large
    // accessibility fonts) instead of covering the face guide.
    panelScroll = new CappedScrollView(this);
    panelScroll.setFillViewport(false);
    panelScroll.addView(panel, new ScrollView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM);
    panelParams.setMargins(dp(12), dp(8), dp(12), dp(12));
    root.addView(panelScroll, panelParams);

    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        int[] bars = systemBarInsets(insets);
        topBar.setPadding(dp(6), dp(6) + bars[0], dp(18), dp(6));
        FrameLayout.LayoutParams params =
            (FrameLayout.LayoutParams) panelScroll.getLayoutParams();
        params.setMargins(dp(12) + bars[2], dp(8), dp(12) + bars[3], dp(12) + bars[1]);
        panelScroll.setLayoutParams(params);
        return insets;
      }
    });
    root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
      @Override public void onLayoutChange(View view, int left, int top, int right,
          int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        updatePreviewLayout(right - left, bottom - top);
        if (overlay != null) overlay.invalidate();
      }
    });
    return root;
  }

  /** {top, bottom, left, right} system bar + cutout insets on any API level. */
  private int[] systemBarInsets(WindowInsets insets) {
    if (Build.VERSION.SDK_INT >= 30) {
      android.graphics.Insets bars = insets.getInsets(
          WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      return new int[] {bars.top, bars.bottom, bars.left, bars.right};
    }
    return new int[] {insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom(),
        insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetRight()};
  }

  /**
   * Sizes the preview surface so the camera image fills the screen by
   * centre-crop instead of being stretched to the display aspect (the old
   * layout visibly squashed faces on tall phones).
   */
  private void updatePreviewLayout(int rootWidth, int rootHeight) {
    if (previewView == null || rootWidth <= 0 || rootHeight <= 0) return;
    int width;
    int height;
    float rootAspect = rootWidth / (float) rootHeight;
    if (rootAspect > previewAspect) {
      width = rootWidth;
      height = Math.round(rootWidth / previewAspect);
    } else {
      height = rootHeight;
      width = Math.round(rootHeight * previewAspect);
    }
    ViewGroup.LayoutParams current = previewView.getLayoutParams();
    if (current.width != width || current.height != height) {
      previewView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
    }
  }

  /** Scroll view whose height is capped to a fraction of its parent. */
  private final class CappedScrollView extends ScrollView {
    CappedScrollView(Activity activity) {
      super(activity);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
      if (parentHeight > 0) {
        int capped = Math.round(parentHeight * 0.54f);
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST);
      }
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
  }

  private TextView pillButton(boolean filled) {
    TextView button = new TextView(this);
    button.setGravity(Gravity.CENTER);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setTextColor(filled ? Color.rgb(6, 20, 40) : COLOR_TEXT);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    if (filled) {
      background.setColor(COLOR_PRIMARY);
    } else {
      background.setColor(0x1AFFFFFF);
      background.setStroke(dp(1), 0x33FFFFFF);
    }
    background.setCornerRadius(dp(26));
    button.setBackground(background);
    button.setClickable(true);
    button.setFocusable(true);
    button.setVisibility(View.GONE);
    return button;
  }

  // --------------------------------------------------------------- UI states

  private void applyInitialUi() {
    if (finished || statusView == null) return;
    if (enrollMode) {
      applyEnrollUi();
      return;
    }
    titleView.setText(revealMode ? "Reveal verification code" : "Unlock Authenticator");
    if (faceStore.isFaceBlocked()) {
      showFaceBlockedPanel();
      return;
    }
    if (enrolled.isEmpty()) {
      captureProgress.setText("");
      stepView.setText(revealMode ? "FACE CHECK" : "FACE UNLOCK");
      setStatus("Face data unavailable", "Use your backup PIN to "
          + (revealMode ? "reveal" : "unlock") + ", or register again.");
      showActions("Use backup PIN", new View.OnClickListener() {
        @Override public void onClick(View view) { showPinUnlockDialog(); }
      }, "Cancel", new View.OnClickListener() {
        @Override public void onClick(View view) { finishCanceled(); }
      }, null, null);
      return;
    }
    stepView.setText(revealMode ? "LIVE FACE CHECK" : "FACE UNLOCK");
    captureProgress.setText("");
    setStatus(revealMode ? "Scan to reveal" : "Looking for your face",
        revealMode
            ? "Fresh live face check required"
                + (accountLabel != null && accountLabel.length() > 0
                    ? " for " + accountLabel : "")
                + ". Follow the prompts — they change every time."
            : "Center your face inside the oval, then follow the prompts. "
                + "They change every time so a photo can't be used.");
    showActions("Use backup PIN", new View.OnClickListener() {
      @Override public void onClick(View view) { showPinUnlockDialog(); }
    }, "Cancel", new View.OnClickListener() {
      @Override public void onClick(View view) { finishCanceled(); }
    }, null, null);
    overlay.setState(OverlayMode.ACTIVE, 0f, OverlayCue.NONE);
    ensureSession();
  }

  private void applyEnrollUi() {
    titleView.setText("Register your face");
    enrollHoldFrames = 0;
    manualCaptureRequested = false;
    enrollStepStartedAtMs = SystemClock.elapsedRealtime();
    captureProgress.setText(enrollDots());
    stepView.setText("STEP " + Math.min(4, enrollStep + 1) + " OF 4");
    overlay.setState(OverlayMode.ACTIVE, captured.size() / 3f, enrollCue());
    if (enrollStep == 0) {
      setStatus("Look straight ahead",
          "Center your face inside the oval in even light. It captures automatically.");
      showActions(null, null, null, null, "Capture now", new View.OnClickListener() {
        @Override public void onClick(View view) { manualCaptureRequested = true; }
      });
    } else if (enrollStep == 1) {
      setStatus("Turn slowly to your left",
          "Turn until it captures — this angle is used later to prove a live head turn.");
      showActions(null, null, null, null, "Capture now", new View.OnClickListener() {
        @Override public void onClick(View view) { manualCaptureRequested = true; }
      });
    } else if (enrollStep == 2) {
      setStatus("Turn slowly to your right",
          "Turn the other way until it captures.");
      showActions(null, null, null, null, "Capture now", new View.OnClickListener() {
        @Override public void onClick(View view) { manualCaptureRequested = true; }
      });
    } else {
      setStatus("Now blink",
          "Look straight, then blink slowly once. This teaches the check what "
              + "your blink looks like.");
      showActions(null, null, null, null, "Skip", new View.OnClickListener() {
        @Override public void onClick(View view) {
          enrollStep = 4;
          askForPinOrFinish();
        }
      });
      blinkBaseFrames = 0;
      blinkBaseOpennessSum = 0f;
      blinkBaseOpenness = -1f;
      blinkDipAtMs = -1L;
    }
  }

  private String enrollDots() {
    StringBuilder dots = new StringBuilder();
    for (int i = 0; i < 4; i++) {
      if (i > 0) dots.append(' ');
      dots.append(enrollStep >= i ? '●' : '○');
    }
    return dots.toString();
  }

  private OverlayCue enrollCue() {
    if (enrollStep == 1) return OverlayCue.TURN_USER_LEFT;
    if (enrollStep == 2) return OverlayCue.TURN_USER_RIGHT;
    if (enrollStep == 3) return OverlayCue.BLINK;
    return OverlayCue.NONE;
  }

  private void showFaceBlockedPanel() {
    session = null;
    lastBlockedSecond = -1;
    stepView.setText(revealMode ? "FACE CHECK" : "FACE UNLOCK");
    captureProgress.setText("");
    setStatus("Face scan paused", blockedMessage());
    showActions("Use backup PIN", new View.OnClickListener() {
      @Override public void onClick(View view) { showPinUnlockDialog(); }
    }, "Cancel", new View.OnClickListener() {
      @Override public void onClick(View view) { finishCanceled(); }
    }, null, null);
    overlay.setState(OverlayMode.ERROR, 0f, OverlayCue.NONE);
  }

  private String blockedMessage() {
    return "Too many failed face attempts. Try again in "
        + faceStore.faceCooldownSeconds() + " seconds, or use your backup PIN now.";
  }

  private void tickBlockedCountdown() {
    if (enrollMode || finished) return;
    if (!faceStore.isFaceBlocked()) {
      if (lastBlockedSecond >= 0) {
        lastBlockedSecond = -1;
        applyInitialUi();
      }
      return;
    }
    int seconds = faceStore.faceCooldownSeconds();
    if (seconds != lastBlockedSecond) {
      lastBlockedSecond = seconds;
      setStatus("Face scan paused", blockedMessage());
    }
  }

  private void showActions(String primaryText, View.OnClickListener primaryListener,
      String secondaryText, View.OnClickListener secondaryListener,
      String auxiliaryText, View.OnClickListener auxiliaryListener) {
    if (primaryText != null) {
      primaryButton.setText(primaryText);
      primaryButton.setOnClickListener(primaryListener);
      primaryButton.setEnabled(true);
      primaryButton.setAlpha(1f);
      primaryButton.setVisibility(View.VISIBLE);
    } else {
      primaryButton.setVisibility(View.GONE);
    }
    if (secondaryText != null) {
      secondaryButton.setText(secondaryText);
      secondaryButton.setOnClickListener(secondaryListener);
      secondaryButton.setEnabled(true);
      secondaryButton.setAlpha(1f);
      secondaryButton.setVisibility(View.VISIBLE);
    } else {
      secondaryButton.setVisibility(View.GONE);
    }
    if (auxiliaryText != null) {
      auxiliaryButton.setText(auxiliaryText);
      auxiliaryButton.setOnClickListener(auxiliaryListener);
      auxiliaryButton.setVisibility(View.VISIBLE);
    } else {
      auxiliaryButton.setVisibility(View.GONE);
    }
  }

  private void setStatus(String status, String instruction) {
    if (statusView == null) return;
    if (!status.equals(statusView.getText().toString())) statusView.setText(status);
    if (instruction != null
        && !instruction.equals(instructionView.getText().toString())) {
      instructionView.setText(instruction);
    }
  }

  private void haptic(int constant) {
    if (statusView != null) statusView.performHapticFeedback(constant);
  }

  private void hapticConfirm() {
    haptic(Build.VERSION.SDK_INT >= 30
        ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
  }

  private void hapticReject() {
    haptic(Build.VERSION.SDK_INT >= 30
        ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
  }

  // ------------------------------------------------------- enrolment samples

  private void onEnrollFrame(FrameResult result) {
    if (finished || !cameraStarted) return;
    if (result.template == null || !result.template.isUsable() || result.signals == null) {
      enrollHoldFrames = 0;
      setStatus("Move to better light",
          "Keep your whole face inside the oval. Avoid strong light behind you.");
      return;
    }
    if (!result.facePresent) {
      enrollHoldFrames = 0;
      setStatus("Center your face", "Keep your whole face inside the oval.");
      return;
    }
    if (enrollStep >= 3) {
      onEnrollBlinkFrame(result);
      return;
    }
    if (manualCaptureRequested) {
      manualCaptureRequested = false;
      tryEnrollCapture(result);
      return;
    }
    if (enrollPoseReady(result.signals)) {
      if (++enrollHoldFrames >= 4) {
        enrollHoldFrames = 0;
        tryEnrollCapture(result);
      } else {
        setStatus("Hold it there…", "Capturing this angle.");
      }
    } else {
      enrollHoldFrames = 0;
      applyEnrollHint();
    }
  }

  private void applyEnrollHint() {
    if (enrollStep == 0) {
      setStatus("Look straight ahead",
          "Center your face inside the oval in even light. It captures automatically.");
    } else if (enrollStep == 1) {
      setStatus("Turn slowly to your left", "Turn a little more, then hold.");
    } else if (enrollStep == 2) {
      setStatus("Turn slowly to your right", "Turn a little more, then hold.");
    }
  }

  /** Geometry gate for automatic capture of the current enrolment pose. */
  private boolean enrollPoseReady(FaceSignals signals) {
    if (!signals.eyesFound) return false;
    if (enrollStep == 0) {
      return Math.abs(signals.eyeMidX - 0.5f) <= 0.11f
          && signals.eyeMidY >= 0.30f && signals.eyeMidY <= 0.60f;
    }
    if (enrollBaseSignals == null || !signals.mouthFound) return false;
    float yaw = signals.eyeMidX - enrollBaseSignals.eyeMidX;
    float parallax = (signals.mouthX - signals.eyeMidX)
        - (enrollBaseSignals.mouthX - enrollBaseSignals.eyeMidX);
    // Sensor coordinates are unmirrored: the user's left is image-right.
    return enrollStep == 1
        ? yaw >= 0.030f && parallax >= 0.008f
        : yaw <= -0.030f && parallax <= -0.008f;
  }

  private void tryEnrollCapture(FrameResult result) {
    FaceTemplate candidate = result.template;
    if (enrollStep > 0 && !captured.isEmpty()) {
      float straightSimilarity = candidate.similarity(captured.get(0));
      if (straightSimilarity > 0.99f) {
        setStatus("Turn a little more",
            "That looked identical to the straight sample, so it can't prove a turn later.");
        return;
      }
      if (straightSimilarity < 0.40f) {
        setStatus("That doesn't match the first sample",
            "Keep the same face in frame for every angle.");
        return;
      }
    }
    captured.add(candidate);
    if (enrollStep == 0) enrollBaseSignals = result.signals;
    hapticConfirm();
    enrollStep = captured.size();
    if (captured.size() >= 3) {
      enrollStep = 3;
    }
    applyEnrollUi();
    if (enrollStep == 1) {
      setStatus("Turn slowly to your left", "Straight sample saved. Now the left angle.");
    } else if (enrollStep == 2) {
      setStatus("Turn slowly to your right", "Left sample saved. Now the right angle.");
    }
  }

  /** Enrolment step 4: verify this person's blink is detectable. */
  private void onEnrollBlinkFrame(FrameResult result) {
    FaceSignals signals = result.signals;
    if (!signals.eyesFound) return;
    long now = SystemClock.elapsedRealtime();
    if (blinkBaseOpenness < 0f) {
      blinkBaseOpennessSum += signals.eyeOpenness;
      if (++blinkBaseFrames >= 6) {
        blinkBaseOpenness = blinkBaseOpennessSum / blinkBaseFrames;
        setStatus("Now blink", "Close both eyes slowly, then open them.");
      } else {
        setStatus("Look straight ahead", "Hold still with your eyes open…");
      }
      return;
    }
    // Same two blink cues as the unlock session: contrast dip, or the eye
    // point jumping up to the brow while the mouth stays put.
    boolean dipped = signals.eyeOpenness < 0.58f * blinkBaseOpenness
        || (enrollBaseSignals != null && signals.mouthFound
            && enrollBaseSignals.eyeMidY - signals.eyeMidY > 0.026f
            && Math.abs(signals.mouthY - enrollBaseSignals.mouthY) < 0.03f);
    if (blinkDipAtMs < 0) {
      if (dipped) blinkDipAtMs = now;
      return;
    }
    if (now - blinkDipAtMs > 1700L) {
      blinkDipAtMs = -1L;
      return;
    }
    if (signals.eyeOpenness > 0.78f * blinkBaseOpenness
        && (enrollBaseSignals == null
            || Math.abs(signals.eyeMidY - enrollBaseSignals.eyeMidY) < 0.02f)) {
      blinkDipAtMs = -1L;
      enrollStep = 4;
      hapticConfirm();
      overlay.setState(OverlayMode.SUCCESS, 1f, OverlayCue.NONE);
      setStatus("Face registered", "Now choose a backup PIN for the times a scan fails.");
      askForPinOrFinish();
    }
  }

  private void tickEnrollBlinkTimeout() {
    if (!enrollMode || finished || enrollStep != 3) return;
    if (SystemClock.elapsedRealtime() - enrollStepStartedAtMs > 12000L) {
      // Never trap the user on the calibration step; unlock challenges adapt
      // to whichever signals are measurable for this person.
      enrollStep = 4;
      askForPinOrFinish();
    }
  }

  private void askForPinOrFinish() {
    if (faceStore.hasPin()) {
      try {
        faceStore.saveTemplates(captured);
        finishSuccess();
      } catch (RuntimeException e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        showActions("Try again", new View.OnClickListener() {
          @Override public void onClick(View view) { askForPinOrFinish(); }
        }, null, null, null, null);
        setStatus("Face could not be saved", "Tap Try again to store the captured face.");
      }
      return;
    }
    showCreatePinDialog();
  }

  private void showCreatePinDialog() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(4), dp(24), 0);
    TextView explanation = text(
        "Use this 6-digit PIN if face unlock fails. It is stored only on this device.",
        13, Color.rgb(95, 99, 104), Typeface.NORMAL);
    explanation.setLineSpacing(dp(2), 1f);
    form.addView(explanation);
    final EditText pin = pinField("PIN");
    final EditText confirm = pinField("Confirm PIN");
    LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    fieldParams.topMargin = dp(14);
    form.addView(pin, fieldParams);
    LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    confirmParams.topMargin = dp(8);
    form.addView(confirm, confirmParams);

    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Set a backup PIN")
        .setView(form)
        .setNegativeButton("Retake", null)
        .setPositiveButton("Save PIN", null)
        .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.setCancelable(true);
    dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
      @Override public void onCancel(DialogInterface ignored) { retakeCaptures(); }
    });
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
            new View.OnClickListener() {
              @Override public void onClick(View view) {
                dialog.dismiss();
                retakeCaptures();
              }
            });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
            new View.OnClickListener() {
              @Override public void onClick(View view) {
                String value = pin.getText().toString();
                String problem = FaceStore.pinProblem(value);
                if (problem != null) {
                  pin.setError(problem);
                  pin.requestFocus();
                  return;
                }
                if (!value.equals(confirm.getText().toString())) {
                  confirm.setError("The PINs do not match.");
                  confirm.requestFocus();
                  return;
                }
                try {
                  faceStore.saveEnrollment(captured, value);
                  dialog.dismiss();
                  finishSuccess();
                } catch (RuntimeException e) {
                  Toast.makeText(FaceLockActivity.this, e.getMessage(),
                      Toast.LENGTH_LONG).show();
                }
              }
            });
        pin.requestFocus();
      }
    });
    dialog.show();
  }

  private EditText pinField(String hint) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setHint(hint);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
    input.setPadding(dp(10), 0, dp(10), 0);
    return input;
  }

  private void retakeCaptures() {
    captured.clear();
    enrollStep = 0;
    enrollBaseSignals = null;
    applyInitialUi();
    setStatus("Let's try again",
        "Capture three angles — straight, your left, your right — then blink.");
  }

  // ------------------------------------------------------------- PIN fallback

  private void showPinUnlockDialog() {
    if (faceStore.isPinBlocked()) {
      Toast.makeText(this, "Too many attempts. Try again in "
          + faceStore.pinCooldownSeconds() + " seconds.", Toast.LENGTH_LONG).show();
      return;
    }
    if (!faceStore.hasPin()) {
      new AlertDialog.Builder(this)
          .setTitle("No backup PIN")
          .setMessage("A backup PIN was not saved on this device. Register your face again "
              + "to create one.")
          .setPositiveButton("OK", null)
          .show();
      return;
    }
    session = null;
    releaseCamera();
    final EditText input = pinField("6-digit PIN");
    LinearLayout box = new LinearLayout(this);
    box.setPadding(dp(24), dp(10), dp(24), 0);
    box.addView(input, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Enter backup PIN")
        .setMessage(revealMode
            ? "Use the PIN you chose when registering your face to reveal this code."
            : "Use the PIN you chose when registering your face.")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(revealMode ? "Reveal" : "Unlock", null)
        .create();
    dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
      @Override public void onDismiss(DialogInterface ignored) {
        if (!finished && (revealMode || !FaceGate.unlocked)) {
          applyInitialUi();
          startCameraIfReady();
        }
      }
    });
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
            new View.OnClickListener() {
              @Override public void onClick(View view) {
                if (faceStore.isPinBlocked()) {
                  dialog.dismiss();
                  Toast.makeText(FaceLockActivity.this, "Too many attempts. Try again in "
                      + faceStore.pinCooldownSeconds() + " seconds.",
                      Toast.LENGTH_LONG).show();
                  return;
                }
                String value = input.getText().toString();
                boolean accepted;
                try {
                  accepted = faceStore.verifyPin(value);
                } catch (RuntimeException e) {
                  Toast.makeText(FaceLockActivity.this, e.getMessage(),
                      Toast.LENGTH_LONG).show();
                  return;
                }
                if (accepted) {
                  faceStore.clearPinFailures();
                  finishSuccess();
                  dialog.dismiss();
                  return;
                }
                faceStore.recordPinFailure();
                if (faceStore.isPinBlocked()) {
                  dialog.dismiss();
                  Toast.makeText(FaceLockActivity.this, "Too many attempts. Try again in "
                      + faceStore.pinCooldownSeconds() + " seconds.",
                      Toast.LENGTH_LONG).show();
                  return;
                }
                input.setError("Incorrect PIN");
                input.setText("");
                input.requestFocus();
              }
            });
        input.requestFocus();
      }
    });
    dialog.show();
  }

  private void finishSuccess() {
    if (finished) return;
    finished = true;
    // A reveal check must never change the app-wide lock state; the caller
    // decides which single code becomes visible.
    if (!revealMode) {
      FaceGate.unlocked = true;
    }
    faceStore.clearPinFailures();
    faceStore.clearFaceFailures();
    releaseCamera();
    restoreScreenLight();
    setResult(RESULT_OK);
    finish();
  }

  private void finishCanceled() {
    if (finished) return;
    finished = true;
    releaseCamera();
    restoreScreenLight();
    setResult(RESULT_CANCELED);
    finish();
  }

  // --------------------------------------------------- permission & problems

  private void showCameraPermissionPanel() {
    if (finished) return;
    showPermissionPanel = true;
    session = null;
    titleView.setText(enrollMode ? "Register your face"
        : revealMode ? "Reveal verification code" : "Unlock Authenticator");
    captureProgress.setText("");
    stepView.setText(enrollMode ? "CAMERA REQUIRED"
        : revealMode ? "FACE CHECK" : "FACE UNLOCK");
    setStatus("Allow camera access",
        "Face registration and recognition happen entirely on this device. "
            + "Fingerprint and Touch ID are not used.");
    showActions("Allow camera access", new View.OnClickListener() {
      @Override public void onClick(View view) {
        requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
      }
    }, "Open app settings", new View.OnClickListener() {
      @Override public void onClick(View view) { openAppSettings(); }
    }, enrollMode ? null : "Use backup PIN", new View.OnClickListener() {
      @Override public void onClick(View view) { showPinUnlockDialog(); }
    });
  }

  private void openAppSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:" + getPackageName())));
    } catch (RuntimeException e) {
      Toast.makeText(this, "Open Settings and allow Camera for Authenticator.",
          Toast.LENGTH_LONG).show();
    }
  }

  private void showCameraProblem(String detail) {
    if (finished) return;
    cameraStarted = false;
    session = null;
    captureProgress.setText("");
    stepView.setText(enrollMode ? "CAMERA REQUIRED"
        : revealMode ? "FACE CHECK" : "FACE UNLOCK");
    setStatus("Camera unavailable", detail);
    if (enrollMode) {
      showActions("Try again", new View.OnClickListener() {
        @Override public void onClick(View view) { retryCamera(); }
      }, "Close", new View.OnClickListener() {
        @Override public void onClick(View view) { finishCanceled(); }
      }, null, null);
    } else {
      showActions("Use backup PIN", new View.OnClickListener() {
        @Override public void onClick(View view) { showPinUnlockDialog(); }
      }, "Try camera again", new View.OnClickListener() {
        @Override public void onClick(View view) { retryCamera(); }
      }, null, null);
    }
  }

  private void retryCamera() {
    cameraFailed = false;
    showPermissionPanel = false;
    applyInitialUi();
    startCameraIfReady();
  }

  // ------------------------------------------------------------------- camera

  @Override public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    startCameraIfReady();
  }

  @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (camera == null) return;
    try {
      camera.stopPreview();
      applyCameraOrientation();
      camera.setPreviewDisplay(holder);
      camera.setPreviewCallback(this);
      camera.startPreview();
    } catch (Exception e) {
      failCamera();
    }
  }

  @Override public void surfaceDestroyed(SurfaceHolder holder) {
    surfaceReady = false;
    releaseCamera();
  }

  private void startCameraIfReady() {
    if (!surfaceReady || camera != null || finished || cameraFailed
        || showPermissionPanel
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    int id = findFrontCamera();
    if (id < 0) {
      cameraFailed = true;
      showCameraProblem("This device does not report a front-facing camera, which is needed "
          + "for face registration.");
      return;
    }
    try {
      camera = Camera.open(id);
      cameraId = id;
      Camera.Parameters parameters = camera.getParameters();
      Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
      if (size != null) {
        parameters.setPreviewSize(size.width, size.height);
      }
      parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
      parameters.setRecordingHint(true);
      int[] fps = chooseFpsRange(parameters.getSupportedPreviewFpsRange());
      if (fps != null) {
        parameters.setPreviewFpsRange(fps[0], fps[1]);
      }
      List<String> focusModes = parameters.getSupportedFocusModes();
      if (focusModes != null
          && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
      } else if (focusModes != null
          && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      }
      camera.setParameters(parameters);
      Camera.Size actualSize = camera.getParameters().getPreviewSize();
      previewWidth = actualSize != null ? actualSize.width : 640;
      previewHeight = actualSize != null ? actualSize.height : 480;
      applyCameraOrientation();
      camera.setPreviewDisplay(surfaceHolder);
      camera.setPreviewCallback(this);
      camera.startPreview();
      cameraStarted = true;
      cameraFailed = false;
      lastFrameAt = 0L;
      applyInitialUi();
    } catch (Exception e) {
      failCamera();
    }
  }

  /**
   * Sets the display rotation for the mirrored selfie preview and records
   * the clockwise rotation that makes raw preview buffers upright, plus the
   * displayed aspect ratio used for the centre-crop layout.
   */
  private void applyCameraOrientation() {
    if (camera == null || cameraId < 0) return;
    Camera.CameraInfo info = new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);
    int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
    int degrees = displayRotation == android.view.Surface.ROTATION_90 ? 90
        : displayRotation == android.view.Surface.ROTATION_180 ? 180
        : displayRotation == android.view.Surface.ROTATION_270 ? 270 : 0;
    sensorRotation = (info.orientation - degrees + 360) % 360;
    int display = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        ? (360 - ((info.orientation + degrees) % 360)) % 360
        : sensorRotation;
    camera.setDisplayOrientation(display);
    boolean swapped = display == 90 || display == 270;
    int displayWidth = swapped ? previewHeight : previewWidth;
    int displayHeight = swapped ? previewWidth : previewHeight;
    if (displayHeight > 0) {
      previewAspect = displayWidth / (float) displayHeight;
      if (rootView != null) {
        updatePreviewLayout(rootView.getWidth(), rootView.getHeight());
      }
    }
  }

  private void failCamera() {
    releaseCamera();
    cameraFailed = true;
    showCameraProblem("The front camera could not be opened. Close other camera apps, "
        + "then try again, or use your backup PIN.");
  }

  private void releaseCamera() {
    cameraStarted = false;
    session = null;
    lastChallengeShown = -1;
    detectorOk = false;
    detectorMulti = false;
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

  private int findFrontCamera() {
    int count = Camera.getNumberOfCameras();
    Camera.CameraInfo info = new Camera.CameraInfo();
    for (int i = 0; i < count; i++) {
      Camera.getCameraInfo(i, info);
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
    }
    return -1;
  }

  /**
   * Picks the preview size closest to this display's aspect ratio so the
   * centre-crop discards as little as possible on tall phones, preferring a
   * width near 1280 for a balance of detail and analysis cost.
   */
  private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
    if (sizes == null || sizes.isEmpty()) return null;
    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
    int displayLong = Math.max(metrics.widthPixels, metrics.heightPixels);
    int displayShort = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
    double targetAspect = displayLong / (double) displayShort;
    Camera.Size best = null;
    double bestScore = Double.MAX_VALUE;
    for (Camera.Size size : sizes) {
      if (size.width > 1920 || size.height > 1920) continue;
      double aspect = Math.max(size.width, size.height)
          / (double) Math.max(1, Math.min(size.width, size.height));
      double score = Math.abs(aspect - targetAspect) * 6.0
          + Math.abs(size.width - 1280) / 1600.0;
      if (size.width < 720) score += 0.6;
      if (score < bestScore) {
        best = size;
        bestScore = score;
      }
    }
    return best;
  }

  private int[] chooseFpsRange(List<int[]> ranges) {
    if (ranges == null || ranges.isEmpty()) return null;
    int[] best = null;
    for (int[] range : ranges) {
      if (range == null || range.length < 2) continue;
      if (range[1] < 24000) continue; // want at least ~24 fps for blink timing
      if (best == null || range[0] > best[0]) best = range;
    }
    return best;
  }

  // ------------------------------------------------------------ frame pipeline

  /** Everything the worker measured on one frame; consumed on the UI thread. */
  private static final class FrameResult {
    long tMs;
    FaceTemplate template;
    FaceSignals signals;
    boolean facePresent;
    boolean multiFace;
    boolean flashOn;
    float simStraight = -1f;
    float simLeft = -1f;
    float simRight = -1f;
    boolean strictStraight;
  }

  @Override public void onPreviewFrame(byte[] data, Camera source) {
    if (data == null || finished || camera == null || worker.isShutdown()) return;
    if (!enrollMode && session == null) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
    if (!processing.compareAndSet(false, true)) return;
    lastFrameAt = now;

    final byte[] frame = data.clone();
    final int width = previewWidth;
    final int height = previewHeight;
    final int rotation = sensorRotation;
    final boolean flash = flashLit;
    final boolean runDetector = (++frameCounter % 2 == 0);
    final List<FaceTemplate> enrolledSnapshot = enrolled;
    try {
      worker.execute(new Runnable() {
        @Override public void run() {
          final FrameResult result = new FrameResult();
          result.tMs = now;
          result.flashOn = flash;
          try {
            result.template = FaceTemplate.fromFrame(frame, width, height, rotation);
            result.signals = FaceSignals.fromFrame(frame, width, height, rotation);
            if (runDetector) {
              updatePlatformDetector(frame, width, height, rotation);
            }
            boolean detectorRecent = now - lastDetectorAtMs <= 900L;
            result.multiFace = detectorRecent && detectorMulti;
            result.facePresent = (detectorRecent && detectorOk)
                || (result.signals != null && result.signals.geometryLooksLikeFace());
            if (result.template != null && !enrolledSnapshot.isEmpty()) {
              result.simStraight = result.template.similarity(enrolledSnapshot.get(0));
              result.simLeft = enrolledSnapshot.size() > 1
                  ? result.template.similarity(enrolledSnapshot.get(1))
                  : result.simStraight;
              result.simRight = enrolledSnapshot.size() > 2
                  ? result.template.similarity(enrolledSnapshot.get(2))
                  : result.simStraight;
              result.strictStraight = result.template.matchesStrict(enrolledSnapshot.get(0));
            }
          } catch (RuntimeException ignored) {
            // A malformed buffer must never crash the scan; the next frame
            // simply replaces it.
          } finally {
            processing.set(false);
          }
          runOnUiThread(new Runnable() {
            @Override public void run() {
              if (finished || isFinishing()) return;
              if (result.signals != null) lastMeanLuma = result.signals.meanLuma;
              if (enrollMode) {
                onEnrollFrame(result);
              } else {
                onUnlockFrame(result);
              }
            }
          });
        }
      });
    } catch (RuntimeException e) {
      processing.set(false);
    }
  }

  /**
   * Runs the platform face detector on a downscaled upright copy of the
   * frame. It only vouches for presence (exactly one plausibly sized face
   * near the middle); photo-versus-person discrimination is the liveness
   * session's job, because this detector happily finds faces in photos.
   */
  private void updatePlatformDetector(byte[] nv21, int width, int height, int rotation) {
    if (detectorBroken) return;
    Bitmap bitmap = null;
    Bitmap converted = null;
    try {
      int uprightWidth = (rotation == 90 || rotation == 270) ? height : width;
      int uprightHeight = (rotation == 90 || rotation == 270) ? width : height;
      int targetWidth = DETECTOR_WIDTH;
      int targetHeight = Math.round(targetWidth * uprightHeight / (float) uprightWidth);
      targetHeight = Math.max(160, Math.min(640, targetHeight));
      if ((targetHeight & 1) == 1) targetHeight++;
      int[] pixels = new int[targetWidth * targetHeight];
      for (int y = 0; y < targetHeight; y++) {
        float uy = y * (uprightHeight - 1) / (float) Math.max(1, targetHeight - 1);
        for (int x = 0; x < targetWidth; x++) {
          float ux = x * (uprightWidth - 1) / (float) Math.max(1, targetWidth - 1);
          int sourceX;
          int sourceY;
          if (rotation == 90) {
            sourceX = (int) uy;
            sourceY = height - 1 - (int) ux;
          } else if (rotation == 180) {
            sourceX = width - 1 - (int) ux;
            sourceY = height - 1 - (int) uy;
          } else if (rotation == 270) {
            sourceX = width - 1 - (int) uy;
            sourceY = (int) ux;
          } else {
            sourceX = (int) ux;
            sourceY = (int) uy;
          }
          sourceX = Math.max(0, Math.min(width - 1, sourceX));
          sourceY = Math.max(0, Math.min(height - 1, sourceY));
          pixels[y * targetWidth + x] = nv21Pixel(nv21, width, height, sourceX, sourceY);
        }
      }
      bitmap = Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
      converted = bitmap.copy(Bitmap.Config.RGB_565, false);
      if (converted == null) return;
      if (faceDetector == null || detectorBitmapWidth != targetWidth
          || detectorBitmapHeight != targetHeight) {
        faceDetector = new FaceDetector(targetWidth, targetHeight, 2);
        detectorBitmapWidth = targetWidth;
        detectorBitmapHeight = targetHeight;
      }
      FaceDetector.Face[] faces = new FaceDetector.Face[2];
      int found = faceDetector.findFaces(converted, faces);
      detectorMulti = found >= 2;
      detectorOk = false;
      if (found == 1 && faces[0] != null && faces[0].confidence() >= 0.30f) {
        PointF midpoint = new PointF();
        faces[0].getMidPoint(midpoint);
        float eyesNorm = faces[0].eyesDistance() / targetWidth;
        detectorOk = eyesNorm >= 0.10f && eyesNorm <= 0.60f
            && midpoint.x >= targetWidth * 0.14f && midpoint.x <= targetWidth * 0.86f
            && midpoint.y >= targetHeight * 0.06f && midpoint.y <= targetHeight * 0.88f;
      }
      lastDetectorAtMs = SystemClock.elapsedRealtime();
    } catch (RuntimeException e) {
      // Some builds ship without the detector's native library. Presence
      // then falls back to the landmark geometry; liveness is unaffected.
      detectorBroken = true;
      detectorOk = false;
      detectorMulti = false;
    } finally {
      if (bitmap != null) bitmap.recycle();
      if (converted != null) converted.recycle();
    }
  }

  private static int nv21Pixel(byte[] nv21, int width, int height, int x, int y) {
    int luma = nv21[y * width + x] & 0xff;
    int chromaIndex = width * height + (y / 2) * width + (x & ~1);
    int v = 128;
    int u = 128;
    if (chromaIndex + 1 < nv21.length) {
      v = nv21[chromaIndex] & 0xff;
      u = nv21[chromaIndex + 1] & 0xff;
    }
    int yy = Math.max(0, luma - 16) * 1192;
    int r = yy + 1634 * (v - 128);
    int g = yy - 833 * (v - 128) - 400 * (u - 128);
    int b = yy + 2066 * (u - 128);
    r = Math.max(0, Math.min(262143, r)) >> 10;
    g = Math.max(0, Math.min(262143, g)) >> 10;
    b = Math.max(0, Math.min(262143, b)) >> 10;
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  // ------------------------------------------------------- unlock / reveal flow

  private void ensureSession() {
    if (enrollMode || session != null || !cameraStarted || finished) return;
    if (enrolled.isEmpty() || faceStore.isFaceBlocked()) return;
    boolean posesDistinct = enrolled.size() >= 3
        && enrolled.get(1).similarity(enrolled.get(2)) <= 0.965f;
    session = new LivenessSession(sessionRandom, posesDistinct,
        SystemClock.elapsedRealtime());
    lastChallengeShown = -1;
  }

  private void onUnlockFrame(FrameResult result) {
    if (finished || session == null) return;
    if (result.template == null || !result.template.isUsable()) {
      LivenessSession.Frame cold = new LivenessSession.Frame();
      cold.tMs = result.tMs;
      cold.usable = false;
      session.onFrame(cold);
      if (!session.isFinished()) {
        setStatus("Move to better light",
            "Keep your whole face inside the oval. Avoid strong backlight.");
        return;
      }
    } else {
      LivenessSession.Frame frame = new LivenessSession.Frame();
      frame.tMs = result.tMs;
      frame.signals = result.signals;
      frame.usable = true;
      frame.facePresent = result.facePresent;
      frame.multiFace = result.multiFace;
      frame.flashOn = result.flashOn;
      frame.simStraight = result.simStraight;
      frame.simLeft = result.simLeft;
      frame.simRight = result.simRight;
      frame.strictStraight = result.strictStraight;
      session.onFrame(frame);
    }
    if (session.isPassed()) {
      onSessionPassed();
    } else if (session.getPhase() == LivenessSession.Phase.FAILED) {
      onSessionFailed(session.getFailReason());
    } else {
      syncSessionUi(result);
    }
  }

  private void syncSessionUi(FrameResult result) {
    LivenessSession.Phase phase = session.getPhase();
    int done = session.getChallengesDone();
    if (done != lastChallengeShown && phase != LivenessSession.Phase.BASELINE) {
      if (lastChallengeShown >= 0) haptic(HapticFeedbackConstants.VIRTUAL_KEY);
      lastChallengeShown = done;
    }
    overlay.setState(OverlayMode.ACTIVE, session.getProgress(), cueForSession());
    if (phase == LivenessSession.Phase.BASELINE) {
      stepView.setText(revealMode ? "LIVE FACE CHECK" : "FACE UNLOCK");
      captureProgress.setText("");
      if (!result.facePresent) {
        setStatus("Center your face", "Fit your whole face inside the oval.");
      } else {
        setStatus("Looking for your face",
            "Look straight at the camera and hold still for a moment.");
      }
      return;
    }
    stepView.setText("LIVE CHECK · " + done + " OF " + session.getChallengesTotal());
    captureProgress.setText(sessionDots(done, session.getChallengesTotal()));
    if (phase == LivenessSession.Phase.RETURN_CENTER) {
      setStatus("Look straight again", "Face the camera directly.");
      return;
    }
    if (phase == LivenessSession.Phase.FINAL_CONFIRM) {
      setStatus("Hold still", "Finishing the check…");
      return;
    }
    LivenessSession.Challenge challenge = session.getCurrentChallenge();
    if (challenge == LivenessSession.Challenge.BLINK) {
      setStatus("Blink slowly", "Close both eyes, then open them.");
    } else if (challenge == LivenessSession.Challenge.TURN_LEFT) {
      setStatus("Turn your head left", "Turn slowly to your left and hold.");
    } else if (challenge == LivenessSession.Challenge.TURN_RIGHT) {
      setStatus("Turn your head right", "Turn slowly to your right and hold.");
    } else if (challenge == LivenessSession.Challenge.NOD_DOWN) {
      setStatus("Nod down", "Dip your chin down slowly and hold.");
    }
  }

  private String sessionDots(int done, int total) {
    StringBuilder dots = new StringBuilder();
    for (int i = 0; i < total; i++) {
      if (i > 0) dots.append(' ');
      dots.append(done > i ? '●' : '○');
    }
    return dots.toString();
  }

  private OverlayCue cueForSession() {
    if (session.getPhase() == LivenessSession.Phase.RETURN_CENTER
        || session.getPhase() == LivenessSession.Phase.FINAL_CONFIRM) {
      return OverlayCue.CENTER;
    }
    LivenessSession.Challenge challenge = session.getCurrentChallenge();
    if (challenge == LivenessSession.Challenge.TURN_LEFT) {
      return OverlayCue.TURN_USER_LEFT;
    }
    if (challenge == LivenessSession.Challenge.TURN_RIGHT) {
      return OverlayCue.TURN_USER_RIGHT;
    }
    if (challenge == LivenessSession.Challenge.NOD_DOWN) return OverlayCue.NOD;
    if (challenge == LivenessSession.Challenge.BLINK) return OverlayCue.BLINK;
    return OverlayCue.NONE;
  }

  private void onSessionPassed() {
    session = null;
    overlay.setState(OverlayMode.SUCCESS, 1f, OverlayCue.NONE);
    hapticConfirm();
    setStatus("Face recognized",
        revealMode ? "Showing your code…" : "Unlocking your codes…");
    primaryButton.setEnabled(false);
    secondaryButton.setEnabled(false);
    ui.postDelayed(new Runnable() {
      @Override public void run() {
        if (!finished) finishSuccess();
      }
    }, 380L);
  }

  private void onSessionFailed(LivenessSession.FailReason reason) {
    session = null;
    if (reason != LivenessSession.FailReason.FACE_LOST
        && reason != LivenessSession.FailReason.NONE) {
      faceStore.recordFaceFailure();
    }
    hapticReject();
    overlay.setState(OverlayMode.ERROR, 0f, OverlayCue.NONE);
    if (faceStore.isFaceBlocked()) {
      showFaceBlockedPanel();
      return;
    }
    String status;
    String instruction;
    if (reason == LivenessSession.FailReason.WRONG_FACE) {
      status = "Face not recognized";
      instruction = "This doesn't match the enrolled face. Try even light without "
          + "anything covering your face, or use your PIN.";
    } else if (reason == LivenessSession.FailReason.SPOOF_SCREEN) {
      status = "That looked like a screen";
      instruction = "Use your real face in front of the camera — photos and videos "
          + "on another device can't pass the live check.";
    } else if (reason == LivenessSession.FailReason.NO_LIGHT_RESPONSE) {
      status = "Couldn't confirm a live face";
      instruction = "Keep your face lit by the screen, away from bright light "
          + "behind you, then try again.";
    } else if (reason == LivenessSession.FailReason.MULTI_FACE) {
      status = "More than one face";
      instruction = "Only one face can be in the frame during a check.";
    } else if (reason == LivenessSession.FailReason.FACE_LOST) {
      status = "Face lost";
      instruction = "Keep your whole face inside the oval until the check finishes.";
    } else {
      status = "That took too long";
      instruction = "Follow each prompt as it appears. The order is different every time.";
    }
    captureProgress.setText("");
    setStatus(status, instruction);
    showActions("Try again", new View.OnClickListener() {
      @Override public void onClick(View view) {
        applyInitialUi();
      }
    }, "Use backup PIN", new View.OnClickListener() {
      @Override public void onClick(View view) { showPinUnlockDialog(); }
    }, "Cancel", new View.OnClickListener() {
      @Override public void onClick(View view) { finishCanceled(); }
    });
  }

  // -------------------------------------------------------------- screen light

  /**
   * Drives the randomised screen flash (full brightness white) while a scan
   * is live, and a gentle fill light when the scene is dark.
   */
  private void updateScreenLight() {
    if (finished) return;
    boolean flash = session != null
        && session.isFlashOn(SystemClock.elapsedRealtime());
    boolean fill = !flash && cameraStarted && lastMeanLuma >= 0f && lastMeanLuma < 45f
        && (session != null || enrollMode);
    flashLit = flash;
    float targetAlpha = flash ? 0.88f : fill ? 0.22f : 0f;
    if (Math.abs(flashView.getAlpha() - targetAlpha) > 0.01f) {
      flashView.setAlpha(targetAlpha);
    }
    WindowManager.LayoutParams params = getWindow().getAttributes();
    float targetBrightness = flash ? 1.0f : fill ? 0.85f
        : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    if (params.screenBrightness != targetBrightness) {
      params.screenBrightness = targetBrightness;
      getWindow().setAttributes(params);
    }
  }

  private void restoreScreenLight() {
    flashLit = false;
    if (flashView != null) flashView.setAlpha(0f);
    try {
      WindowManager.LayoutParams params = getWindow().getAttributes();
      params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
      getWindow().setAttributes(params);
    } catch (RuntimeException ignored) {}
  }

  // ------------------------------------------------------------------- helpers

  private TextView text(String value, float sp, int color, int style) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextColor(color);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTypeface(Typeface.create("sans-serif", style));
    return view;
  }

  private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  /**
   * Face guide overlay: dims everything outside the oval, draws a progress
   * ring around it, and shows a directional cue for the current challenge.
   * The oval is laid out in the measured free space between the top bar and
   * the bottom panel, so it is never clipped on any screen size.
   */
  private final class FaceOverlay extends View {

    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF ringOval = new RectF();
    private final Path path = new Path();
    private OverlayMode mode = OverlayMode.ACTIVE;
    private OverlayCue cue = OverlayCue.NONE;
    private float progress;

    FaceOverlay(Activity activity) {
      super(activity);
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      setClickable(false);
      dim.setColor(0xA6000000);
      clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      border.setStyle(Paint.Style.STROKE);
      ringTrack.setStyle(Paint.Style.STROKE);
      ringTrack.setColor(0x33FFFFFF);
      ringTrack.setStrokeWidth(dp(3));
      ring.setStyle(Paint.Style.STROKE);
      ring.setStrokeWidth(dp(4.5f));
      ring.setStrokeCap(Paint.Cap.ROUND);
      cuePaint.setStyle(Paint.Style.STROKE);
      cuePaint.setStrokeCap(Paint.Cap.ROUND);
      cuePaint.setStrokeJoin(Paint.Join.ROUND);
      checkPaint.setStyle(Paint.Style.STROKE);
      checkPaint.setStrokeCap(Paint.Cap.ROUND);
      checkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setState(OverlayMode newMode, float newProgress, OverlayCue newCue) {
      mode = newMode;
      progress = Math.max(0f, Math.min(1f, newProgress));
      cue = newCue;
      invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int width = getWidth();
      int height = getHeight();
      if (width <= 0 || height <= 0) return;

      float guideTop = topBarView != null && topBarView.getBottom() > 0
          ? topBarView.getBottom() + dp(10) : height * 0.11f;
      float guideBottom = panelScroll != null && panelScroll.getTop() > 0
          ? panelScroll.getTop() - dp(12) : height * 0.60f;
      if (guideBottom - guideTop < dp(120)) {
        guideBottom = Math.max(guideTop + dp(120), height * 0.60f);
      }
      float availableHeight = guideBottom - guideTop;
      float ovalWidth = Math.min(width * 0.76f, dp(330));
      float ovalHeight = Math.min(ovalWidth * 1.26f, availableHeight * 0.96f);
      ovalWidth = ovalHeight / 1.26f;
      float cx = width / 2f;
      float cy = guideTop + availableHeight / 2f;
      oval.set(cx - ovalWidth / 2f, cy - ovalHeight / 2f,
          cx + ovalWidth / 2f, cy + ovalHeight / 2f);

      int save = canvas.saveLayer(0, 0, width, height, null);
      canvas.drawRect(0, 0, width, height, dim);
      canvas.drawOval(oval, clear);
      canvas.restoreToCount(save);

      int accent = mode == OverlayMode.SUCCESS ? 0xFF34C77B
          : mode == OverlayMode.ERROR ? 0xFFE5534B : 0xCCFFFFFF;
      border.setColor(accent);
      border.setStrokeWidth(mode == OverlayMode.ACTIVE ? dp(2) : dp(3.5f));
      canvas.drawOval(oval, border);

      float inset = dp(9);
      ringOval.set(oval.left - inset, oval.top - inset,
          oval.right + inset, oval.bottom + inset);
      canvas.drawOval(ringOval, ringTrack);
      if (progress > 0.005f) {
        ring.setColor(mode == OverlayMode.SUCCESS ? 0xFF34C77B : COLOR_PRIMARY);
        canvas.drawArc(ringOval, -90f, 360f * progress, false, ring);
      }

      if (mode == OverlayMode.SUCCESS) {
        checkPaint.setColor(0xFF34C77B);
        checkPaint.setStrokeWidth(dp(6));
        float unit = Math.min(ovalWidth, ovalHeight) / 10f;
        path.reset();
        path.moveTo(cx - 1.6f * unit, cy + 0.1f * unit);
        path.lineTo(cx - 0.5f * unit, cy + 1.2f * unit);
        path.lineTo(cx + 1.8f * unit, cy - 1.3f * unit);
        canvas.drawPath(path, checkPaint);
        return;
      }
      if (mode != OverlayMode.ACTIVE) return;

      cuePaint.setColor(COLOR_PRIMARY);
      cuePaint.setStrokeWidth(dp(4));
      float chevron = dp(11);
      if (cue == OverlayCue.TURN_USER_LEFT) {
        // Nose moves towards the right edge of the mirrored preview.
        drawChevron(canvas, oval.right + dp(16), cy, chevron, 1, 0);
        drawChevron(canvas, oval.right + dp(30), cy, chevron, 1, 0);
      } else if (cue == OverlayCue.TURN_USER_RIGHT) {
        drawChevron(canvas, oval.left - dp(16), cy, chevron, -1, 0);
        drawChevron(canvas, oval.left - dp(30), cy, chevron, -1, 0);
      } else if (cue == OverlayCue.NOD) {
        drawChevron(canvas, cx, oval.bottom + dp(18), chevron, 0, 1);
        drawChevron(canvas, cx, oval.bottom + dp(32), chevron, 0, 1);
      } else if (cue == OverlayCue.BLINK) {
        float eyeY = oval.top - dp(26);
        path.reset();
        path.moveTo(cx - dp(17), eyeY);
        path.quadTo(cx, eyeY - dp(13), cx + dp(17), eyeY);
        path.quadTo(cx, eyeY + dp(13), cx - dp(17), eyeY);
        path.close();
        canvas.drawPath(path, cuePaint);
        cuePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, eyeY, dp(4), cuePaint);
        cuePaint.setStyle(Paint.Style.STROKE);
      }
    }

    private void drawChevron(Canvas canvas, float x, float y, float size,
        int directionX, int directionY) {
      path.reset();
      if (directionX != 0) {
        path.moveTo(x - directionX * size * 0.55f, y - size);
        path.lineTo(x + directionX * size * 0.55f, y);
        path.lineTo(x - directionX * size * 0.55f, y + size);
      } else {
        path.moveTo(x - size, y - directionY * size * 0.55f);
        path.lineTo(x, y + directionY * size * 0.55f);
        path.lineTo(x + size, y - directionY * size * 0.55f);
      }
      canvas.drawPath(path, cuePaint);
    }
  }
}
