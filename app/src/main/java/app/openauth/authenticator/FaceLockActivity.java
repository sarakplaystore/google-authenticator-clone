package app.openauth.authenticator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
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
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom, on-device face registration and face unlock.
 *
 * <p>This screen deliberately does not use Android's biometric APIs. It asks
 * for three front-camera samples during setup and compares live camera frames
 * with the stored descriptors afterwards. A six-digit backup PIN is created
 * during setup so a failed scan cannot lock the user out permanently.</p>
 */
@SuppressWarnings("deprecation")
public final class FaceLockActivity extends Activity
    implements SurfaceHolder.Callback, Camera.PreviewCallback {
  public static final String EXTRA_MODE = "app.openauth.authenticator.FACE_MODE";
  public static final String MODE_ENROLL = "enroll";
  public static final String MODE_UNLOCK = "unlock";

  private static final int REQUEST_CAMERA = 63;
  private static final int COLOR_BACKGROUND = Color.rgb(9, 12, 16);
  private static final int COLOR_PANEL = Color.rgb(24, 29, 36);
  private static final int COLOR_TEXT = Color.rgb(244, 247, 250);
  private static final int COLOR_SECONDARY = Color.rgb(174, 184, 196);
  private static final int COLOR_PRIMARY = Color.rgb(138, 180, 248);

  private final Handler ui = new Handler();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean processing = new AtomicBoolean(false);
  private final List<FaceTemplate> captured = new ArrayList<FaceTemplate>();

  private boolean enrollMode;
  private boolean finished;
  private boolean scanning;
  private boolean cameraStarted;
  private boolean cameraFailed;
  private boolean surfaceReady;
  private boolean captureRequested;
  private boolean showPermissionPanel;
  private int matchedFrames;
  private long lastFrameAt;
  private long scanStartedAt;
  private int previewWidth;
  private int previewHeight;
  private int sensorRotation;

  private FaceStore faceStore;
  private List<FaceTemplate> enrolled = new ArrayList<FaceTemplate>();
  private Camera camera;
  private SurfaceHolder surfaceHolder;

  private FaceOverlay overlay;
  private TextView titleView;
  private TextView stepView;
  private TextView statusView;
  private TextView instructionView;
  private TextView primaryButton;
  private TextView secondaryButton;
  private TextView auxiliaryButton;
  private TextView captureProgress;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    enrollMode = MODE_ENROLL.equals(getIntent().getStringExtra(EXTRA_MODE));
    faceStore = new FaceStore(this);
    if (!enrollMode) {
      try {
        enrolled = faceStore.loadTemplates();
      } catch (RuntimeException e) {
        enrolled = new ArrayList<FaceTemplate>();
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      }
    }

    configureWindow();
    setContentView(buildScreen());
    applyInitialUi();
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraIfReady();
    } else {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }
  }

  @Override protected void onResume() {
    super.onResume();
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
    releaseCamera();
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

  private void configureWindow() {
    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        | WindowManager.LayoutParams.FLAG_SECURE);
    window.setStatusBarColor(COLOR_BACKGROUND);
    window.setNavigationBarColor(COLOR_BACKGROUND);
    window.getDecorView().setSystemUiVisibility(0);
  }

  private View buildScreen() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(COLOR_BACKGROUND);

    SurfaceView preview = new SurfaceView(this);
    surfaceHolder = preview.getHolder();
    surfaceHolder.addCallback(this);
    root.addView(preview, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    overlay = new FaceOverlay(this);
    root.addView(overlay, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(10), dp(10), dp(18), dp(10));
    topBar.setBackgroundColor(0x66000000);
    TextView close = text("✕", 22, Color.WHITE, Typeface.NORMAL);
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Cancel");
    close.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { onBackPressed(); }
    });
    topBar.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
    titleView = text("", 17, Color.WHITE, Typeface.BOLD);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    titleParams.leftMargin = dp(8);
    topBar.addView(titleView, titleParams);
    root.addView(topBar, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(22), dp(18), dp(22), dp(18));
    GradientDrawable panelBackground = new GradientDrawable();
    panelBackground.setColor(COLOR_PANEL);
    panelBackground.setCornerRadius(dp(24));
    panel.setBackground(panelBackground);
    panel.setElevation(dp(12));

    stepView = text("", 12, COLOR_PRIMARY, Typeface.BOLD);
    stepView.setLetterSpacing(0.12f);
    panel.addView(stepView);

    statusView = text("", 23, COLOR_TEXT, Typeface.BOLD);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    statusParams.topMargin = dp(5);
    panel.addView(statusView, statusParams);

    instructionView = text("", 14, COLOR_SECONDARY, Typeface.NORMAL);
    instructionView.setLineSpacing(dp(2), 1f);
    LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    instructionParams.topMargin = dp(6);
    panel.addView(instructionView, instructionParams);

    captureProgress = text("", 12, COLOR_SECONDARY, Typeface.NORMAL);
    captureProgress.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    progressParams.topMargin = dp(12);
    panel.addView(captureProgress, progressParams);

    primaryButton = pillButton(true);
    LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
    primaryParams.topMargin = dp(14);
    panel.addView(primaryButton, primaryParams);

    secondaryButton = pillButton(false);
    LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    secondaryParams.topMargin = dp(8);
    panel.addView(secondaryButton, secondaryParams);

    auxiliaryButton = text("", 13, COLOR_SECONDARY, Typeface.BOLD);
    auxiliaryButton.setGravity(Gravity.CENTER);
    auxiliaryButton.setPadding(dp(6), dp(10), dp(6), dp(4));
    auxiliaryButton.setClickable(true);
    auxiliaryButton.setFocusable(true);
    LinearLayout.LayoutParams auxiliaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    auxiliaryParams.topMargin = dp(2);
    panel.addView(auxiliaryButton, auxiliaryParams);

    FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM);
    panelParams.setMargins(dp(12), dp(12), dp(12), dp(12));
    root.addView(panel, panelParams);

    TextView deviceNote = text("On-device face recognition · no fingerprint or Touch ID",
        11, 0xFF8A94A3, Typeface.NORMAL);
    deviceNote.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    noteParams.topMargin = dp(70);
    root.addView(deviceNote, noteParams);

    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        boolean modern = android.os.Build.VERSION.SDK_INT >= 35;
        int top = modern ? insets.getSystemWindowInsetTop() : 0;
        int bottom = modern ? insets.getSystemWindowInsetBottom() : 0;
        topBar.setPadding(dp(10), dp(10) + top, dp(18), dp(10));
        panel.setPadding(dp(22), dp(18), dp(22), dp(18) + bottom);
        return insets;
      }
    });
    return root;
  }

  private TextView pillButton(boolean filled) {
    TextView button = new TextView(this);
    button.setGravity(Gravity.CENTER);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setTextColor(filled ? Color.rgb(6, 20, 40) : COLOR_TEXT);
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

  private void applyInitialUi() {
    if (finished || statusView == null) return;
    if (enrollMode) {
      titleView.setText("Register your face");
      captureProgress.setText(captured.size() == 0 ? "● ○ ○" : captured.size() == 1 ? "● ● ○" : "● ● ●");
      stepView.setText("STEP " + Math.min(3, captured.size() + 1) + " OF 3");
      String[] prompts = {"Look straight ahead", "Turn your head slightly left",
          "Turn your head slightly right"};
      int index = Math.min(prompts.length - 1, captured.size());
      statusView.setText(prompts[index]);
      instructionView.setText(captured.isEmpty()
          ? "Center your face inside the oval in even light, then capture."
          : "Sample " + captured.size() + " saved. Hold the next angle inside the oval.");
      showActions("Capture", new View.OnClickListener() {
        @Override public void onClick(View view) { requestCapture(); }
      }, null, null, null, null);
      setPrimaryEnabled(cameraStarted && !processing.get());
    } else {
      titleView.setText("Unlock Authenticator");
      captureProgress.setText("");
      stepView.setText("FACE UNLOCK");
      statusView.setText("Looking for your face");
      instructionView.setText(enrolled.isEmpty()
          ? "Face data is not available. Use your backup PIN."
          : "Center your face inside the oval. Recognition stays on this device.");
      showActions("Use backup PIN", new View.OnClickListener() {
        @Override public void onClick(View view) { showPinUnlockDialog(); }
      }, "Cancel", new View.OnClickListener() {
        @Override public void onClick(View view) { finishCanceled(); }
      }, null, null);
      if (enrolled.isEmpty()) {
        setPrimaryEnabled(true);
        setStatus("Face data unavailable", "Use your backup PIN to unlock, or register again.");
      }
      scanning = !enrolled.isEmpty() && cameraStarted;
      scanStartedAt = SystemClock.elapsedRealtime();
      matchedFrames = 0;
    }
  }

  private void showActions(String primaryText, View.OnClickListener primaryListener,
      String secondaryText, View.OnClickListener secondaryListener,
      String auxiliaryText, View.OnClickListener auxiliaryListener) {
    primaryButton.setText(primaryText);
    primaryButton.setOnClickListener(primaryListener);
    primaryButton.setEnabled(true);
    primaryButton.setAlpha(1f);
    primaryButton.setVisibility(View.VISIBLE);
    if (secondaryText != null) {
      secondaryButton.setText(secondaryText);
      secondaryButton.setOnClickListener(secondaryListener);
      secondaryButton.setEnabled(true);
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

  private void setPrimaryEnabled(boolean enabled) {
    primaryButton.setEnabled(enabled);
    primaryButton.setAlpha(enabled ? 1f : 0.45f);
  }

  private void setStatus(String status, String instruction) {
    if (statusView == null) return;
    if (!status.equals(statusView.getText().toString())) statusView.setText(status);
    if (instruction != null
        && !instruction.equals(instructionView.getText().toString())) {
      instructionView.setText(instruction);
    }
  }

  private void requestCapture() {
    if (finished || !enrollMode) return;
    if (!cameraStarted) {
      setStatus("Camera is starting", "Wait a moment, then try capturing again.");
      return;
    }
    if (processing.get()) return;
    captureRequested = true;
    setPrimaryEnabled(false);
    primaryButton.setText("Capturing…");
    setStatus("Hold still", "Reading your face on this device…");
    ui.postDelayed(captureTimeout, 3000L);
  }

  private final Runnable captureTimeout = new Runnable() {
    @Override public void run() {
      if (finished || !enrollMode || !captureRequested) return;
      captureRequested = false;
      primaryButton.setText("Capture");
      setPrimaryEnabled(cameraStarted);
      if (!cameraStarted) {
        failCamera();
      } else {
        setStatus("Camera is warming up", "Tap Capture again in a moment.");
      }
    }
  };

  private void onCaptureFinished(FaceTemplate template) {
    if (finished) return;
    primaryButton.setText("Capture");
    if (template == null || !template.isUsable()) {
      setPrimaryEnabled(cameraStarted);
      setStatus("Could not read your face",
          "Move into brighter, even light and keep your whole face inside the oval.");
      return;
    }
    captured.add(template);
    if (captured.size() >= 3) {
      captureProgress.setText("● ● ●");
      setPrimaryEnabled(false);
      setStatus("Face captured", "Now choose a backup PIN for the times when a scan fails.");
      askForPinOrFinish();
      return;
    }
    applyInitialUi();
    setStatus(captured.size() == 1 ? "Look left" : "Look right",
        "Sample " + captured.size() + " saved. Capture the next angle.");
    setPrimaryEnabled(cameraStarted);
  }

  private void askForPinOrFinish() {
    if (faceStore.hasPin()) {
      try {
        faceStore.saveTemplates(captured);
        finishSuccess();
      } catch (RuntimeException e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        primaryButton.setText("Try again");
        primaryButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View view) { askForPinOrFinish(); }
        });
        setPrimaryEnabled(true);
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
    matchedFrames = 0;
    applyInitialUi();
    setStatus("Let's try again", "Capture three angles: straight, slightly left, slightly right.");
    setPrimaryEnabled(cameraStarted);
  }

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
    scanning = false;
    releaseCamera();
    final EditText input = pinField("6-digit PIN");
    LinearLayout box = new LinearLayout(this);
    box.setPadding(dp(24), dp(10), dp(24), 0);
    box.addView(input, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Enter backup PIN")
        .setMessage("Use the PIN you chose when registering your face.")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Unlock", null)
        .create();
    dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
      @Override public void onDismiss(DialogInterface ignored) {
        if (!finished && !FaceGate.unlocked) {
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
    FaceGate.unlocked = true;
    faceStore.clearPinFailures();
    releaseCamera();
    setResult(RESULT_OK);
    finish();
  }

  private void finishCanceled() {
    if (finished) return;
    finished = true;
    releaseCamera();
    setResult(RESULT_CANCELED);
    finish();
  }

  private void showCameraPermissionPanel() {
    if (finished) return;
    showPermissionPanel = true;
    scanStartedAt = 0L;
    titleView.setText(enrollMode ? "Register your face" : "Unlock Authenticator");
    captureProgress.setText("");
    if (enrollMode) stepView.setText("CAMERA REQUIRED"); else stepView.setText("FACE UNLOCK");
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
    setPrimaryEnabled(true);
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
    scanStartedAt = 0L;
    captureProgress.setText("");
    stepView.setText(enrollMode ? "CAMERA REQUIRED" : "FACE UNLOCK");
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
    setPrimaryEnabled(true);
  }

  private void retryCamera() {
    cameraFailed = false;
    showPermissionPanel = false;
    applyInitialUi();
    startCameraIfReady();
  }

  @Override public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    startCameraIfReady();
  }

  @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (camera == null) return;
    try {
      camera.stopPreview();
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
      Camera.Parameters parameters = camera.getParameters();
      Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
      if (size != null) {
        parameters.setPreviewSize(size.width, size.height);
      }
      parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
      Camera.Size actualSize = parameters.getPreviewSize();
      previewWidth = actualSize != null ? actualSize.width : 640;
      previewHeight = actualSize != null ? actualSize.height : 480;
      List<String> focusModes = parameters.getSupportedFocusModes();
      if (focusModes != null
          && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      } else if (focusModes != null
          && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
      } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      }
      camera.setParameters(parameters);

      Camera.CameraInfo info = new Camera.CameraInfo();
      Camera.getCameraInfo(id, info);
      int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
      int degrees = displayRotation == android.view.Surface.ROTATION_90 ? 90
          : displayRotation == android.view.Surface.ROTATION_180 ? 180
          : displayRotation == android.view.Surface.ROTATION_270 ? 270 : 0;
      // The preview buffer needs the same clockwise rotation that makes the
      // image upright; the mirrored preview uses a separate display angle.
      sensorRotation = (info.orientation - degrees + 360) % 360;
      int display = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
          ? (360 - ((info.orientation + degrees) % 360)) % 360
          : sensorRotation;
      camera.setDisplayOrientation(display);

      camera.setPreviewDisplay(surfaceHolder);
      camera.setPreviewCallback(this);
      camera.startPreview();
      cameraStarted = true;
      cameraFailed = false;
      overlay.setMatched(false);
      applyInitialUi();
    } catch (Exception e) {
      failCamera();
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
    scanning = false;
    captureRequested = false;
    ui.removeCallbacks(captureTimeout);
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

  private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
    if (sizes == null || sizes.isEmpty()) return null;
    Camera.Size best = null;
    double bestScore = Double.MAX_VALUE;
    for (Camera.Size size : sizes) {
      if (size.width > 1920 || size.height > 1080) continue;
      double aspect = size.width / (double) size.height;
      double aspectError = Math.min(Math.abs(aspect - (16.0 / 9.0)),
          Math.abs(aspect - (4.0 / 3.0)));
      double score = aspectError * 8.0 + Math.abs(size.width - 1280) / 1280.0;
      if (size.width < 640) score += 0.5;
      if (score < bestScore) {
        best = size;
        bestScore = score;
      }
    }
    return best;
  }

  @Override public void onPreviewFrame(byte[] data, Camera source) {
    if (data == null || finished || camera == null || worker.isShutdown()) return;
    if (enrollMode) {
      if (!captureRequested) return;
    } else {
      if (!scanning) return;
      long now = SystemClock.elapsedRealtime();
      if (now - lastFrameAt < 260L) return;
      lastFrameAt = now;
    }
    if (!processing.compareAndSet(false, true)) return;
    if (enrollMode) captureRequested = false;

    final byte[] frame = data.clone();
    final int width = previewWidth;
    final int height = previewHeight;
    final int rotation = sensorRotation;
    final boolean captureMode = enrollMode;
    try {
      worker.execute(new Runnable() {
        @Override public void run() {
          FaceTemplate template = null;
          try {
            template = FaceTemplate.fromFrame(frame, width, height, rotation);
          } catch (RuntimeException ignored) {
            template = null;
          } finally {
            processing.set(false);
          }
          final FaceTemplate result = template;
          runOnUiThread(new Runnable() {
            @Override public void run() {
              if (finished || isFinishing()) return;
              if (captureMode) {
                ui.removeCallbacks(captureTimeout);
                onCaptureFinished(result);
              } else {
                onScanFrame(result);
              }
            }
          });
        }
      });
    } catch (RuntimeException e) {
      processing.set(false);
      if (enrollMode) captureRequested = true;
    }
  }

  private void onScanFrame(FaceTemplate template) {
    if (finished || !scanning) return;
    if (template == null) return;
    if (!template.isUsable()) {
      setStatus("Move to better light",
          "Keep your whole face inside the oval. Avoid strong backlight.");
      return;
    }
    float best = 0f;
    for (FaceTemplate stored : enrolled) {
      float score = template.similarity(stored);
      if (score > best) best = score;
    }
    if (best >= FaceTemplate.MATCH_THRESHOLD) {
      matchedFrames++;
      if (matchedFrames >= 2) {
        scanning = false;
        overlay.setMatched(true);
        statusView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        setStatus("Face recognized", "Unlocking your codes…");
        setPrimaryEnabled(false);
        secondaryButton.setEnabled(false);
        ui.postDelayed(new Runnable() {
          @Override public void run() {
            if (!finished) finishSuccess();
          }
        }, 380L);
      } else {
        setStatus("Hold still", "One more read and your codes will open.");
      }
      return;
    }
    matchedFrames = Math.max(0, matchedFrames - 1);
    if (best >= FaceTemplate.MATCH_THRESHOLD - 0.07f) {
      setStatus("Almost there", "Center your face and hold still for a moment.");
    } else if (scanStartedAt > 0
        && SystemClock.elapsedRealtime() - scanStartedAt > 12000L) {
      setStatus("Still looking", "Try even lighting, or use your backup PIN.");
    } else {
      setStatus("Looking for your face",
          "Center your face inside the oval. Recognition stays on this device.");
    }
  }

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

  /** Draws the oval guide and dims everything outside it. */
  private final class FaceOverlay extends View {
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean matched;

    FaceOverlay(Activity activity) {
      super(activity);
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      dim.setColor(0xA6000000);
      clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      border.setStyle(Paint.Style.STROKE);
      border.setStrokeWidth(dp(2));
      glow.setStyle(Paint.Style.STROKE);
      glow.setStrokeWidth(dp(5));
      glow.setColor(COLOR_PRIMARY);
    }

    void setMatched(boolean matched) {
      this.matched = matched;
      invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float width = Math.min(getWidth() * 0.68f, dp(320));
      float height = Math.min(width * 1.26f, getHeight() * 0.40f);
      width = height / 1.26f;
      float cx = getWidth() / 2f;
      float cy = getHeight() * 0.36f;
      oval.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);

      int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
      canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
      canvas.drawOval(oval, clear);
      canvas.restoreToCount(save);

      border.setColor(matched ? 0xFF34C77B : 0xCCFFFFFF);
      border.setStrokeWidth(matched ? dp(4) : dp(2));
      canvas.drawOval(oval, border);
      if (matched) {
        glow.setAlpha(90);
        canvas.drawOval(oval, glow);
      }
    }
  }
}
