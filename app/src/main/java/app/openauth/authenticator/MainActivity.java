package app.openauth.authenticator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Main screen: a local, offline-first TOTP authenticator. */
public final class MainActivity extends Activity {
  private static final int REQUEST_SCAN = 41;
  private static final int REQUEST_FACE = 42;
  private static final int REQUEST_REVEAL = 43;
  /** How long a code stays visible after a successful face check. */
  private static final long REVEAL_WINDOW_MS = 30000L;
  private static final String STATE_PENDING_OTP = "pending_otp_uri";
  private static final int COLOR_BACKGROUND = Color.rgb(248, 249, 250);
  private static final int COLOR_SURFACE = Color.WHITE;
  private static final int COLOR_TEXT = Color.rgb(32, 33, 36);
  private static final int COLOR_SECONDARY = Color.rgb(95, 99, 104);
  private static final int COLOR_PRIMARY = Color.rgb(26, 115, 232);
  private static final int COLOR_DIVIDER = Color.rgb(237, 239, 243);

  private final Handler handler = new Handler();
  private final List<AccountCard> visibleCards = new ArrayList<AccountCard>();
  /** Account id -> wall-clock time at which the revealed code locks again. */
  private final HashMap<String, Long> revealUntil = new HashMap<String, Long>();
  private AccountStore store;
  private FaceStore faceStore;
  private ArrayList<Account> accounts = new ArrayList<Account>();
  private LinearLayout accountList;
  private EditText searchField;
  private View addButton;
  private View searchAction;
  private String searchQuery = "";
  private boolean searchVisible;
  private boolean accountsLoaded;
  private boolean facePromptActive;
  private boolean revealRequestActive;
  private boolean autoPromptShown;
  private boolean suppressAccountRender;
  private String pendingOtpUri;
  private String pendingRevealAccountId;

  private final Runnable clockTick = new Runnable() {
    @Override public void run() {
      long now = System.currentTimeMillis();
      for (AccountCard card : visibleCards) {
        card.update(now);
      }
      handler.postDelayed(this, 250L);
    }
  };

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    configureSystemBars();
    store = new AccountStore(this);
    faceStore = new FaceStore(this);
    if (state != null) {
      pendingOtpUri = state.getString(STATE_PENDING_OTP);
    }
    setContentView(buildScreen());
    renderLockedState();
    handleIncomingIntent(getIntent());
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (pendingOtpUri != null) {
      state.putString(STATE_PENDING_OTP, pendingOtpUri);
    }
  }

  @Override protected void onResume() {
    super.onResume();
    FaceGate.internalNavigation = false;
    // A result callback normally clears this, but the system can destroy the
    // face screen without one (for example when a VIEW intent takes over).
    facePromptActive = false;
    revealRequestActive = false;
    pendingRevealAccountId = null;
    if (FaceGate.promptOnNextResume) {
      FaceGate.promptOnNextResume = false;
      autoPromptShown = false;
    }
    if (FaceGate.unlocked) {
      showUnlockedUi();
    } else {
      renderLockedState();
      maybeAutoPromptFace();
    }
    handler.removeCallbacks(clockTick);
    handler.post(clockTick);
  }

  @Override protected void onPause() {
    handler.removeCallbacks(clockTick);
    // Never leave a revealed code on screen behind another app, the recents
    // preview, or after the screen turns off.
    hideAllReveals();
    if (FaceGate.unlocked) {
      renderLockedState();
    }
    super.onPause();
  }

  @Override protected void onStop() {
    super.onStop();
    if (!isChangingConfigurations() && !FaceGate.internalNavigation && !facePromptActive) {
      FaceGate.unlocked = false;
      autoPromptShown = false;
      accountsLoaded = false;
      accounts.clear();
      hideAllReveals();
      renderLockedState();
    }
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIncomingIntent(intent);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_REVEAL) {
      String accountId = pendingRevealAccountId;
      pendingRevealAccountId = null;
      revealRequestActive = false;
      FaceGate.internalNavigation = false;
      if (resultCode == RESULT_OK && FaceGate.unlocked && accountId != null) {
        revealUntil.put(accountId, System.currentTimeMillis() + REVEAL_WINDOW_MS);
        Toast.makeText(this, "Code revealed for 30 seconds", Toast.LENGTH_SHORT).show();
      } else if (resultCode != RESULT_OK) {
        Toast.makeText(this, "Face check canceled — code stays hidden", Toast.LENGTH_SHORT).show();
      }
      showUnlockedUi();
      return;
    }
    if (requestCode == REQUEST_FACE) {
      facePromptActive = false;
      FaceGate.internalNavigation = false;
      if (resultCode == RESULT_OK) {
        FaceGate.unlocked = true;
        faceStore.clearPinFailures();
        autoPromptShown = true;
        showUnlockedUi();
      } else if (FaceGate.unlocked) {
        showUnlockedUi();
      } else {
        renderLockedState();
      }
      return;
    }
    if (requestCode == REQUEST_SCAN && resultCode == RESULT_OK && data != null) {
      String value = data.getStringExtra(ScanActivity.EXTRA_RESULT);
      importOtpUri(value);
    }
  }

  private void configureSystemBars() {
    Window window = getWindow();
    window.setStatusBarColor(COLOR_BACKGROUND);
    window.setNavigationBarColor(COLOR_BACKGROUND);
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        | WindowManager.LayoutParams.FLAG_SECURE);
    int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
    if (android.os.Build.VERSION.SDK_INT >= 26) {
      flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    }
    window.getDecorView().setSystemUiVisibility(flags);
  }

  private void maybeAutoPromptFace() {
    if (facePromptActive || FaceGate.unlocked || autoPromptShown) return;
    autoPromptShown = true;
    startFacePrompt(!faceStore.isEnrolled());
  }

  private void startFacePrompt(boolean enroll) {
    if (facePromptActive) return;
    facePromptActive = true;
    autoPromptShown = true;
    Intent intent = new Intent(this, FaceLockActivity.class);
    intent.putExtra(FaceLockActivity.EXTRA_MODE,
        enroll ? FaceLockActivity.MODE_ENROLL : FaceLockActivity.MODE_UNLOCK);
    startActivityForResult(intent, REQUEST_FACE);
  }

  private void startFaceUpdate() {
    if (facePromptActive) return;
    FaceGate.internalNavigation = true;
    facePromptActive = true;
    autoPromptShown = true;
    Intent intent = new Intent(this, FaceLockActivity.class);
    intent.putExtra(FaceLockActivity.EXTRA_MODE, FaceLockActivity.MODE_ENROLL);
    startActivityForResult(intent, REQUEST_FACE);
  }

  private void showUnlockedUi() {
    if (!FaceGate.unlocked || suppressAccountRender) {
      if (!FaceGate.unlocked && !suppressAccountRender) renderLockedState();
      return;
    }
    if (!accountsLoaded) {
      loadAccounts();
    }
    if (addButton != null) addButton.setVisibility(View.VISIBLE);
    if (searchAction != null) searchAction.setVisibility(View.VISIBLE);
    renderAccounts();
    if (pendingOtpUri != null) {
      String value = pendingOtpUri;
      pendingOtpUri = null;
      importOtpUri(value);
    }
  }

  private void loadAccounts() {
    try {
      accounts = new ArrayList<Account>(store.load());
    } catch (RuntimeException e) {
      accounts = new ArrayList<Account>();
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
    accountsLoaded = true;
  }

  private void renderLockedState() {
    if (accountList == null) return;
    suppressAccountRender = true;
    try {
      visibleCards.clear();
      accountList.removeAllViews();
      accountList.addView(buildLockState());
      if (addButton != null) addButton.setVisibility(View.GONE);
      if (searchAction != null) searchAction.setVisibility(View.GONE);
      if (searchField != null) {
        searchVisible = false;
        searchQuery = "";
        searchField.setVisibility(View.GONE);
        if (searchField.getText().length() > 0) {
          searchField.setText("");
        }
      }
    } finally {
      suppressAccountRender = false;
    }
  }

  private View buildLockState() {
    final boolean enrolled = faceStore.isEnrolled();
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setPadding(dp(20), dp(38), dp(20), dp(30));

    BrandMark mark = new BrandMark(this);
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(84), dp(84));
    markParams.bottomMargin = dp(20);
    box.addView(mark, markParams);

    TextView title = text(enrolled ? "Authenticator is locked" : "Set up face unlock",
        22, COLOR_TEXT, Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    box.addView(title);

    TextView description = text(enrolled
            ? "Use your face to unlock the app. Every code reveal needs its own face check. "
                + "Face data never leaves this device."
            : "Face registration is required before your codes can be shown. "
                + "Three camera samples are stored on this device.",
        15, COLOR_SECONDARY, Typeface.NORMAL);
    description.setGravity(Gravity.CENTER);
    description.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(12);
    box.addView(description, descriptionParams);

    TextView primary = pillButton(enrolled ? "Unlock with face" : "Register my face",
        COLOR_PRIMARY, Color.WHITE);
    primary.setContentDescription(enrolled ? "Unlock with face" : "Register my face");
    primary.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { startFacePrompt(!enrolled); }
    });
    LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
    primaryParams.topMargin = dp(22);
    box.addView(primary, primaryParams);

    if (enrolled) {
      TextView pin = pillButton("Use backup PIN", 0x00000000, COLOR_PRIMARY);
      pin.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View view) { showPinUnlockDialog(); }
      });
      LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
      pinParams.topMargin = dp(8);
      box.addView(pin, pinParams);
    }

    TextView privacy = text("Custom face recognition · no fingerprint or Touch ID",
        12, COLOR_SECONDARY, Typeface.BOLD);
    privacy.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    privacyParams.topMargin = dp(24);
    box.addView(privacy, privacyParams);

    TextView note = text("Face unlock is a convenience lock. Your account secrets stay "
        + "encrypted with the Android Keystore.", 11, 0xFF80868B, Typeface.NORMAL);
    note.setGravity(Gravity.CENTER);
    note.setLineSpacing(dp(2), 1f);
    LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    noteParams.topMargin = dp(8);
    box.addView(note, noteParams);
    return box;
  }

  private TextView pillButton(String label, int backgroundColor, int textColor) {
    TextView button = new TextView(this);
    button.setText(label);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setTextColor(textColor);
    button.setGravity(Gravity.CENTER);
    button.setClickable(true);
    button.setFocusable(true);
    GradientDrawable background = new GradientDrawable();
    background.setColor(backgroundColor);
    background.setCornerRadius(dp(26));
    if (backgroundColor == 0) {
      background.setColor(0x00000000);
    }
    button.setBackground(background);
    return button;
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
    final EditText input = pinInput("6-digit PIN");
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
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
            new View.OnClickListener() {
              @Override public void onClick(View view) {
                if (faceStore.isPinBlocked()) {
                  dialog.dismiss();
                  Toast.makeText(MainActivity.this, "Too many attempts. Try again in "
                      + faceStore.pinCooldownSeconds() + " seconds.",
                      Toast.LENGTH_LONG).show();
                  return;
                }
                boolean accepted;
                try {
                  accepted = faceStore.verifyPin(input.getText().toString());
                } catch (RuntimeException e) {
                  Toast.makeText(MainActivity.this, e.getMessage(),
                      Toast.LENGTH_LONG).show();
                  return;
                }
                if (accepted) {
                  faceStore.clearPinFailures();
                  FaceGate.unlocked = true;
                  autoPromptShown = true;
                  dialog.dismiss();
                  showUnlockedUi();
                  return;
                }
                faceStore.recordPinFailure();
                if (faceStore.isPinBlocked()) {
                  dialog.dismiss();
                  Toast.makeText(MainActivity.this, "Too many attempts. Try again in "
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

  private EditText pinInput(String hint) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setHint(hint);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
    input.setPadding(dp(10), 0, dp(10), 0);
    return input;
  }

  private void showFaceSettings() {
    final String[] options = {"Update face registration", "Change backup PIN",
        "How face unlock works"};
    new AlertDialog.Builder(this)
        .setTitle("Face unlock")
        .setItems(options, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              startFaceUpdate();
            } else if (which == 1) {
              showChangePinDialog();
            } else {
              showFaceInfo();
            }
          }
        })
        .show();
  }

  private void showChangePinDialog() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(4), dp(24), 0);
    final EditText pin = pinInput("New 6-digit PIN");
    final EditText confirm = pinInput("Confirm PIN");
    LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    pinParams.topMargin = dp(10);
    form.addView(pin, pinParams);
    LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    confirmParams.topMargin = dp(8);
    form.addView(confirm, confirmParams);
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Change backup PIN")
        .setView(form)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create();
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
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
                  faceStore.updatePin(value);
                  Toast.makeText(MainActivity.this, "Backup PIN updated",
                      Toast.LENGTH_SHORT).show();
                  dialog.dismiss();
                } catch (RuntimeException e) {
                  Toast.makeText(MainActivity.this, e.getMessage(),
                      Toast.LENGTH_LONG).show();
                }
              }
            });
        pin.requestFocus();
      }
    });
    dialog.show();
  }

  private void showFaceInfo() {
    new AlertDialog.Builder(this)
        .setTitle("How face unlock works")
        .setMessage("Authenticator uses its own camera-based face lock. It does not call "
            + "Android's biometric prompt and does not use your fingerprint, Touch ID, "
            + "or the device's face unlock.\n\nThree numeric face descriptors are stored "
            + "encrypted on this device; camera images are never saved. If a scan fails, "
            + "use your 6-digit backup PIN.\n\nThis is a convenience lock, not "
            + "security-grade biometrics: a photo of you could potentially fool it. Your "
            + "TOTP secrets remain encrypted with the Android Keystore.")
        .setPositiveButton("Got it", null)
        .show();
  }

  private View buildScreen() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(COLOR_BACKGROUND);
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
        int top = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetTop() : 0;
        int bottom = android.os.Build.VERSION.SDK_INT >= 35 ? insets.getSystemWindowInsetBottom() : 0;
        view.setPadding(0, top, 0, bottom);
        return insets;
      }
    });

    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    root.addView(page, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    page.addView(buildHeader(), new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(0, 0, 0, dp(100));
    page.addView(scroll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    accountList = new LinearLayout(this);
    accountList.setOrientation(LinearLayout.VERTICAL);
    accountList.setPadding(dp(18), dp(8), dp(18), dp(20));
    scroll.addView(accountList, new ScrollView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    LinearLayout addButton = new LinearLayout(this);
    MainActivity.this.addButton = addButton;
    addButton.setOrientation(LinearLayout.HORIZONTAL);
    addButton.setGravity(Gravity.CENTER);
    addButton.setPadding(dp(18), dp(14), dp(20), dp(14));
    addButton.setBackground(roundDrawable(COLOR_PRIMARY, dp(28)));
    addButton.setElevation(dp(7));
    addButton.setClickable(true);
    addButton.setFocusable(true);
    addButton.setContentDescription("Add an account");
    addButton.addView(new ActionIconView(this, ActionIconView.PLUS, Color.WHITE),
        new LinearLayout.LayoutParams(dp(20), dp(20)));
    TextView addLabel = text("Add a code", 15, Color.WHITE, Typeface.BOLD);
    LinearLayout.LayoutParams addLabelParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    addLabelParams.leftMargin = dp(8);
    addButton.addView(addLabel, addLabelParams);
    addButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { showAddOptions(); }
    });
    FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.RIGHT | Gravity.BOTTOM);
    buttonParams.setMargins(dp(16), dp(12), dp(20), dp(20));
    root.addView(addButton, buttonParams);
    return root;
  }

  private View buildHeader() {
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(20), dp(14), dp(12), dp(8));

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout titles = new LinearLayout(this);
    titles.setOrientation(LinearLayout.VERTICAL);
    TextView title = text("Authenticator", 24, COLOR_TEXT, Typeface.BOLD);
    TextView subtitle = text("Verification codes", 13, COLOR_SECONDARY, Typeface.NORMAL);
    titles.addView(title);
    LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    subtitleParams.topMargin = dp(2);
    titles.addView(subtitle, subtitleParams);
    titleRow.addView(titles, new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    ActionIconView search = new ActionIconView(this, ActionIconView.SEARCH, COLOR_SECONDARY);
    searchAction = search;
    search.setContentDescription("Search accounts");
    titleRow.addView(search, new LinearLayout.LayoutParams(dp(44), dp(44)));
    search.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { toggleSearch(); }
    });

    ActionIconView more = new ActionIconView(this, ActionIconView.MORE, COLOR_SECONDARY);
    more.setContentDescription("More options");
    LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(40), dp(44));
    titleRow.addView(more, moreParams);
    more.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { showMainMenu(view); }
    });
    header.addView(titleRow);

    searchField = new EditText(this);
    searchField.setSingleLine(true);
    searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    searchField.setHint("Search accounts");
    searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    searchField.setPadding(dp(12), 0, dp(12), 0);
    searchField.setVisibility(View.GONE);
    LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    searchParams.topMargin = dp(8);
    header.addView(searchField, searchParams);
    searchField.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        searchQuery = s.toString().trim().toLowerCase(Locale.US);
        if (FaceGate.unlocked && !suppressAccountRender) renderAccounts();
      }
      @Override public void afterTextChanged(Editable s) {}
    });
    return header;
  }

  private void toggleSearch() {
    if (searchField == null) return;
    searchVisible = !searchVisible;
    searchField.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
    if (!searchVisible) {
      searchQuery = "";
      searchField.setText("");
    } else {
      searchField.requestFocus();
    }
  }

  private void showMainMenu(View anchor) {
    PopupMenu popup = new PopupMenu(this, anchor);
    popup.getMenu().add(Menu.NONE, 1, 0, "About");
    popup.getMenu().add(Menu.NONE, 2, 1, "How codes work");
    if (FaceGate.unlocked) {
      popup.getMenu().add(Menu.NONE, 3, 2, "Face unlock settings");
      popup.getMenu().add(Menu.NONE, 4, 3, "Lock now");
    }
    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
      @Override public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == 1) {
          showAbout();
          return true;
        }
        if (item.getItemId() == 2) {
          showHowItWorks();
          return true;
        }
        if (item.getItemId() == 3 && FaceGate.unlocked) {
          showFaceSettings();
          return true;
        }
        if (item.getItemId() == 4 && FaceGate.unlocked) {
          lockNow();
          return true;
        }
        return false;
      }
    });
    popup.show();
  }

  private void lockNow() {
    FaceGate.unlocked = false;
    autoPromptShown = true;
    accountsLoaded = false;
    accounts.clear();
    hideAllReveals();
    renderLockedState();
    startFacePrompt(!faceStore.isEnrolled());
  }

  private void showAbout() {
    new AlertDialog.Builder(this)
        .setTitle("About Authenticator")
        .setMessage("An independent, offline-first authenticator for standard TOTP accounts. "
            + "Your setup keys are encrypted on this device with the Android Keystore. "
            + "This app is not affiliated with Google.")
        .setPositiveButton("Done", null)
        .show();
  }

  private void showHowItWorks() {
    new AlertDialog.Builder(this)
        .setTitle("About verification codes")
        .setMessage("Codes are generated on this device from your setup key and the current time. "
            + "They refresh automatically, even when you are offline. "
            + "Your codes stay masked until you tap the locked eye icon and pass a face check; "
            + "they hide again after 30 seconds or when you leave the app. If a code is rejected, "
            + "check that your device date and time are set automatically.")
        .setPositiveButton("Got it", null)
        .show();
  }

  private void renderAccounts() {
    if (accountList == null) return;
    if (!FaceGate.unlocked || suppressAccountRender) {
      if (!FaceGate.unlocked && !suppressAccountRender) renderLockedState();
      return;
    }
    accountList.removeAllViews();
    visibleCards.clear();

    ArrayList<Account> filtered = new ArrayList<Account>();
    for (Account account : accounts) {
      String searchable = (account.getIssuer() + " " + account.getName()).toLowerCase(Locale.US);
      if (searchQuery.length() == 0 || searchable.contains(searchQuery)) {
        filtered.add(account);
      }
    }

    if (accounts.isEmpty()) {
      accountList.addView(buildEmptyState());
      return;
    }
    if (filtered.isEmpty()) {
      TextView noMatches = text("No matching accounts", 15, COLOR_SECONDARY, Typeface.NORMAL);
      noMatches.setGravity(Gravity.CENTER);
      noMatches.setPadding(dp(8), dp(42), dp(8), dp(24));
      accountList.addView(noMatches);
      return;
    }

    TextView count = text(filtered.size() == 1 ? "1 account" : filtered.size() + " accounts",
        13, COLOR_SECONDARY, Typeface.NORMAL);
    LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    countParams.leftMargin = dp(2);
    countParams.bottomMargin = dp(10);
    accountList.addView(count, countParams);

    long now = System.currentTimeMillis();
    for (Account account : filtered) {
      AccountCard card = new AccountCard(account);
      visibleCards.add(card);
      accountList.addView(card.view, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      LinearLayout.LayoutParams spacing = (LinearLayout.LayoutParams) card.view.getLayoutParams();
      spacing.bottomMargin = dp(12);
      card.view.setLayoutParams(spacing);
      card.update(now);
    }
  }

  private View buildEmptyState() {
    LinearLayout empty = new LinearLayout(this);
    empty.setOrientation(LinearLayout.VERTICAL);
    empty.setGravity(Gravity.CENTER_HORIZONTAL);
    empty.setPadding(dp(14), dp(48), dp(14), dp(36));

    BrandMark mark = new BrandMark(this);
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(76), dp(76));
    markParams.bottomMargin = dp(20);
    empty.addView(mark, markParams);

    TextView title = text("Your codes, all in one place", 21, COLOR_TEXT, Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    empty.addView(title);
    TextView description = text(
        "Add an account by scanning its QR code or entering its setup key. "
            + "Codes are generated offline and stay on this device.",
        15, COLOR_SECONDARY, Typeface.NORMAL);
    description.setGravity(Gravity.CENTER);
    description.setLineSpacing(dp(4), 1f);
    LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(12);
    empty.addView(description, descriptionParams);

    TextView note = text("Supports standard time-based (TOTP) accounts", 13,
        COLOR_SECONDARY, Typeface.NORMAL);
    note.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    noteParams.topMargin = dp(20);
    empty.addView(note, noteParams);
    return empty;
  }

  private final class AccountCard {
    final Account account;
    final LinearLayout view;
    final TextView codeText;
    final CountdownView countdown;
    final EyeIconView eye;
    final TextView hint;
    boolean revealed;
    long lastStep = Long.MIN_VALUE;

    AccountCard(final Account account) {
      this.account = account;
      view = new LinearLayout(MainActivity.this);
      view.setOrientation(LinearLayout.VERTICAL);
      view.setPadding(dp(17), dp(13), dp(15), dp(12));
      GradientDrawable base = roundDrawable(COLOR_SURFACE, dp(18));
      if (android.os.Build.VERSION.SDK_INT >= 21) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F1A73E8), base, null));
      } else {
        view.setBackground(base);
      }
      view.setElevation(dp(1));
      view.setClickable(true);
      view.setFocusable(true);
      view.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          // Copying would put the digits on the clipboard where they could be
          // read, so a locked code asks for a face check first.
          if (MainActivity.this.isRevealed(account.getId(), System.currentTimeMillis())) {
            copyCode(AccountCard.this);
          } else {
            requestReveal(account);
          }
        }
      });

      LinearLayout top = new LinearLayout(MainActivity.this);
      top.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout identity = new LinearLayout(MainActivity.this);
      identity.setOrientation(LinearLayout.VERTICAL);
      TextView issuer = text(account.getIssuerOrDefault(), 14, COLOR_TEXT, Typeface.BOLD);
      TextView name = text(account.getName(), 13, COLOR_SECONDARY, Typeface.NORMAL);
      identity.addView(issuer);
      LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      nameParams.topMargin = dp(3);
      identity.addView(name, nameParams);
      top.addView(identity, new LinearLayout.LayoutParams(0,
          ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

      ActionIconView more = new ActionIconView(MainActivity.this, ActionIconView.MORE, COLOR_SECONDARY);
      more.setContentDescription("Account options");
      top.addView(more, new LinearLayout.LayoutParams(dp(36), dp(38)));
      more.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) { showAccountMenu(account, v); }
      });
      view.addView(top);

      LinearLayout codeRow = new LinearLayout(MainActivity.this);
      codeRow.setGravity(Gravity.CENTER_VERTICAL);
      codeText = text("------", 31, COLOR_TEXT, Typeface.NORMAL);
      codeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      codeText.setLetterSpacing(0.045f);
      codeRow.addView(codeText, new LinearLayout.LayoutParams(0,
          ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      eye = new EyeIconView(MainActivity.this);
      LinearLayout.LayoutParams eyeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
      eyeParams.rightMargin = dp(2);
      codeRow.addView(eye, eyeParams);
      eye.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          if (MainActivity.this.isRevealed(account.getId(), System.currentTimeMillis())) {
            revealUntil.remove(account.getId());
            update(System.currentTimeMillis());
          } else {
            requestReveal(account);
          }
        }
      });
      countdown = new CountdownView(MainActivity.this, account.getPeriod());
      codeRow.addView(countdown, new LinearLayout.LayoutParams(dp(48), dp(48)));
      LinearLayout.LayoutParams codeRowParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      codeRowParams.topMargin = dp(8);
      view.addView(codeRow, codeRowParams);

      View divider = new View(MainActivity.this);
      divider.setBackgroundColor(COLOR_DIVIDER);
      LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
      dividerParams.topMargin = dp(9);
      dividerParams.bottomMargin = dp(8);
      view.addView(divider, dividerParams);
      hint = text("", 12, COLOR_SECONDARY, Typeface.NORMAL);
      view.addView(hint);
    }

    void update(long now) {
      long step = now / (account.getPeriod() * 1000L);
      boolean nowRevealed = MainActivity.this.isRevealed(account.getId(), now);
      if (step != lastStep || nowRevealed != revealed) {
        lastStep = step;
        revealed = nowRevealed;
        applyRevealedState();
        refreshCode(now);
      }
      countdown.update(now);
    }

    private void refreshCode(long now) {
      try {
        String code = Totp.code(account, now);
        if (revealed) {
          int split = code.length() / 2;
          codeText.setText(code.substring(0, split) + " " + code.substring(split));
          codeText.setContentDescription(code);
        } else {
          codeText.setText(maskedCode(code.length()));
          codeText.setContentDescription(
              "Code hidden. Tap the eye to reveal it with a face check.");
        }
      } catch (RuntimeException e) {
        codeText.setText("------");
      }
    }

    private void applyRevealedState() {
      eye.setRevealed(revealed);
      if (revealed) {
        eye.setContentDescription("Hide code");
        hint.setText("Tap to copy code · locks automatically");
      } else {
        eye.setContentDescription("Locked. Tap to reveal code with a face check.");
        hint.setText("Locked · tap the eye for a face check");
      }
    }
  }

  /** Bullets keep the number of digits visible without exposing the code. */
  private String maskedCode(int length) {
    int split = length / 2;
    StringBuilder masked = new StringBuilder();
    for (int i = 0; i < length; i++) {
      if (i == split) masked.append(' ');
      masked.append('\u2022');
    }
    return masked.toString();
  }

  private void copyCode(AccountCard card) {
    try {
      String code = Totp.code(card.account, System.currentTimeMillis());
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText(card.account.getIssuerOrDefault(), code));
      Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
    } catch (RuntimeException e) {
      Toast.makeText(this, "This code could not be generated", Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * Hides every code. A fresh face check is needed before any code can be
   * shown or copied again.
   */
  private void hideAllReveals() {
    revealUntil.clear();
  }

  private boolean isRevealed(String accountId, long now) {
    Long until = revealUntil.get(accountId);
    if (until == null) return false;
    if (now >= until) {
      revealUntil.remove(accountId);
      return false;
    }
    return true;
  }

  private void requestReveal(final Account account) {
    if (revealRequestActive) return;
    if (!faceStore.isEnrolled()) {
      Toast.makeText(this, "Register your face in Face unlock settings to reveal codes.",
          Toast.LENGTH_LONG).show();
      return;
    }
    revealRequestActive = true;
    pendingRevealAccountId = account.getId();
    FaceGate.internalNavigation = true;
    Intent intent = new Intent(this, FaceLockActivity.class);
    intent.putExtra(FaceLockActivity.EXTRA_MODE, FaceLockActivity.MODE_REVEAL);
    intent.putExtra(FaceLockActivity.EXTRA_ACCOUNT_LABEL, account.getIssuerOrDefault());
    startActivityForResult(intent, REQUEST_REVEAL);
  }

  private void showAccountMenu(final Account account, View anchor) {
    PopupMenu popup = new PopupMenu(this, anchor);
    popup.getMenu().add(Menu.NONE, 1, 0, "Rename");
    popup.getMenu().add(Menu.NONE, 2, 1, "Remove");
    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
      @Override public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == 1) {
          showRenameDialog(account);
          return true;
        }
        if (item.getItemId() == 2) {
          confirmRemove(account);
          return true;
        }
        return false;
      }
    });
    popup.show();
  }

  private void showRenameDialog(final Account account) {
    final EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(account.getName());
    input.setSelectAllOnFocus(true);
    input.setHint("Account name");
    input.setPadding(dp(8), dp(8), dp(8), dp(8));
    LinearLayout box = new LinearLayout(this);
    box.setPadding(dp(24), 0, dp(24), 0);
    box.addView(input, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Rename account")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save", null)
        .create();
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View view) {
            String name = input.getText().toString().trim();
            if (name.length() == 0) {
              input.setError("Enter a name");
              return;
            }
            replaceAccount(account, account.withName(name));
            dialog.dismiss();
          }
        });
      }
    });
    dialog.show();
  }

  private void confirmRemove(final Account account) {
    new AlertDialog.Builder(this)
        .setTitle("Remove account?")
        .setMessage("Remove " + account.getName() + " from this device? This cannot be undone.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            removeAccount(account);
          }
        })
        .show();
  }

  private void replaceAccount(Account original, Account replacement) {
    ArrayList<Account> updated = new ArrayList<Account>(accounts);
    for (int i = 0; i < updated.size(); i++) {
      if (updated.get(i).getId().equals(original.getId())) {
        updated.set(i, replacement);
        break;
      }
    }
    persistAndShow(updated);
  }

  private void removeAccount(Account account) {
    ArrayList<Account> updated = new ArrayList<Account>();
    for (Account existing : accounts) {
      if (!existing.getId().equals(account.getId())) updated.add(existing);
    }
    persistAndShow(updated);
  }

  private void persistAndShow(ArrayList<Account> updated) {
    try {
      store.save(updated);
      accounts = updated;
      renderAccounts();
    } catch (RuntimeException e) {
      Toast.makeText(this, "Could not save changes securely", Toast.LENGTH_LONG).show();
    }
  }

  private void showAddOptions() {
    final String[] options = {"Scan a QR code", "Enter a setup key"};
    new AlertDialog.Builder(this)
        .setTitle("Add an account")
        .setItems(options, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              FaceGate.internalNavigation = true;
              startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQUEST_SCAN);
            } else {
              showManualEntry();
            }
          }
        })
        .show();
  }

  private void showManualEntry() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), 0, dp(24), dp(4));
    final EditText name = addFormField(form, "Account name", "name@example.com",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    final EditText issuer = addFormField(form, "Service (optional)", "e.g. Example",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    final EditText secret = addFormField(form, "Setup key", "Paste your Base32 key",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    ScrollView scroller = new ScrollView(this);
    scroller.setFillViewport(false);
    scroller.addView(form);
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Enter a setup key")
        .setView(scroller)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Add", null)
        .create();
    dialog.setOnShowListener(new DialogInterface.OnShowListener() {
      @Override public void onShow(DialogInterface ignored) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View view) {
            try {
              Account account = Account.fromManual(name.getText().toString(),
                  issuer.getText().toString(), secret.getText().toString());
              if (containsDuplicate(account)) {
                secret.setError("This setup key is already added");
                return;
              }
              ArrayList<Account> updated = new ArrayList<Account>(accounts);
              updated.add(account);
              persistAndShow(updated);
              if (findAccount(account.getId()) != null) {
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "Account added", Toast.LENGTH_SHORT).show();
              }
            } catch (IllegalArgumentException e) {
              secret.setError(e.getMessage());
              secret.requestFocus();
            }
          }
        });
      }
    });
    dialog.show();
  }

  private EditText addFormField(LinearLayout form, String label, String hint, int inputType) {
    TextView labelView = text(label, 12, COLOR_SECONDARY, Typeface.BOLD);
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    labelParams.topMargin = dp(12);
    form.addView(labelView, labelParams);
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    input.setHint(hint);
    input.setInputType(inputType);
    input.setPadding(dp(8), dp(6), dp(8), dp(6));
    form.addView(input, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
    return input;
  }

  private boolean containsDuplicate(Account candidate) {
    for (Account account : accounts) {
      if (account.getSecret().equals(candidate.getSecret())
          && account.getAlgorithm().equals(candidate.getAlgorithm())
          && account.getDigits() == candidate.getDigits()
          && account.getPeriod() == candidate.getPeriod()) {
        return true;
      }
    }
    return false;
  }

  private Account findAccount(String id) {
    for (Account account : accounts) if (account.getId().equals(id)) return account;
    return null;
  }

  private void importOtpUri(String value) {
    if (value == null || value.length() == 0) return;
    if (!FaceGate.unlocked) {
      pendingOtpUri = value;
      return;
    }
    try {
      Account account = Account.fromOtpAuthUri(value);
      if (containsDuplicate(account)) {
        Toast.makeText(this, "This account is already added", Toast.LENGTH_LONG).show();
        return;
      }
      ArrayList<Account> updated = new ArrayList<Account>(accounts);
      updated.add(account);
      persistAndShow(updated);
      if (findAccount(account.getId()) != null) {
        Toast.makeText(this, "Account added", Toast.LENGTH_SHORT).show();
      }
    } catch (IllegalArgumentException e) {
      new AlertDialog.Builder(this)
          .setTitle("QR code not supported")
          .setMessage(e.getMessage() + "\n\nUse a standard otpauth://totp QR code, or enter the setup key manually.")
          .setPositiveButton("OK", null)
          .show();
    }
  }

  private void handleIncomingIntent(Intent intent) {
    if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
      return;
    }
    importOtpUri(intent.getData().toString());
  }

  private TextView text(String value, float sp, int color, int style) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextColor(color);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTypeface(Typeface.create("sans-serif", style));
    return view;
  }

  private GradientDrawable roundDrawable(int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(radius);
    return drawable;
  }

  private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  private final class CountdownView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle = new RectF();
    private final int period;
    private float progress = 1f;
    private int seconds;

    CountdownView(Context context, int period) {
      super(context);
      this.period = period;
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      textPaint.setTextSize(dp(11));
    }

    void update(long now) {
      long periodMillis = period * 1000L;
      long remaining = periodMillis - (now % periodMillis);
      progress = Math.max(0.02f, Math.min(1f, remaining / (float) periodMillis));
      seconds = (int) Math.ceil(remaining / 1000.0);
      invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float stroke = dp(3);
      float radius = Math.min(getWidth(), getHeight()) / 2f - stroke - dp(1);
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      circle.set(cx - radius, cy - radius, cx + radius, cy + radius);
      ringPaint.setStyle(Paint.Style.STROKE);
      ringPaint.setStrokeWidth(stroke);
      ringPaint.setStrokeCap(Paint.Cap.ROUND);
      ringPaint.setColor(0xFFE5E8ED);
      canvas.drawArc(circle, 0, 360, false, ringPaint);
      ringPaint.setColor(progress < 0.22f ? 0xFFE37400 : COLOR_PRIMARY);
      canvas.drawArc(circle, -90, 360f * progress, false, ringPaint);
      textPaint.setColor(COLOR_SECONDARY);
      Paint.FontMetrics metrics = textPaint.getFontMetrics();
      float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
      canvas.drawText(String.valueOf(seconds), cx, baseline, textPaint);
    }
  }

  private final class BrandMark extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shield = new Path();

    BrandMark(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float r = Math.min(getWidth(), getHeight()) * 0.48f;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xFFE8F0FE);
      canvas.drawCircle(cx, cy, r, paint);
      shield.reset();
      shield.moveTo(cx, cy - r * 0.56f);
      shield.lineTo(cx + r * 0.45f, cy - r * 0.38f);
      shield.lineTo(cx + r * 0.42f, cy + r * 0.08f);
      shield.quadTo(cx + r * 0.35f, cy + r * 0.45f, cx, cy + r * 0.62f);
      shield.quadTo(cx - r * 0.35f, cy + r * 0.45f, cx - r * 0.42f, cy + r * 0.08f);
      shield.lineTo(cx - r * 0.45f, cy - r * 0.38f);
      shield.close();
      paint.setColor(COLOR_PRIMARY);
      canvas.drawPath(shield, paint);
      paint.setColor(Color.WHITE);
      canvas.drawCircle(cx, cy - r * 0.06f, r * 0.12f, paint);
      canvas.drawRoundRect(cx - r * 0.075f, cy + r * 0.02f, cx + r * 0.075f,
          cy + r * 0.34f, r * 0.07f, r * 0.07f, paint);
    }
  }

  /**
   * Eye control for a locked code. The crossed-out eye carries a small
   * padlock while the code is hidden; a successful face check opens it.
   */
  private static final class EyeIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path eye = new Path();
    private boolean revealed;

    EyeIconView(Context context) {
      super(context);
      setClickable(true);
      setFocusable(true);
      TypedValue value = new TypedValue();
      if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
          value, true)) {
        setBackgroundResource(value.resourceId);
      }
    }

    void setRevealed(boolean revealed) {
      if (this.revealed != revealed) {
        this.revealed = revealed;
        invalidate();
      }
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float unit = Math.min(getWidth(), getHeight()) / 44f;
      float halfWidth = 10f * unit;

      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2f * unit);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setColor(revealed ? COLOR_PRIMARY : COLOR_SECONDARY);
      eye.reset();
      eye.moveTo(cx - halfWidth, cy);
      eye.quadTo(cx, cy - 9f * unit, cx + halfWidth, cy);
      eye.quadTo(cx, cy + 9f * unit, cx - halfWidth, cy);
      eye.close();
      canvas.drawPath(eye, paint);

      if (revealed) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 3f * unit, paint);
        return;
      }

      // Crossed-out eye communicates "hidden"; the padlock below says a face
      // check is required rather than a simple tap.
      canvas.drawLine(cx - 8f * unit, cy + 9f * unit,
          cx + 8f * unit, cy - 9f * unit, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(COLOR_SURFACE);
      canvas.drawCircle(cx + 7f * unit, cy + 8f * unit, 8f * unit, paint);
      paint.setColor(COLOR_SECONDARY);
      canvas.drawRoundRect(cx + 3.5f * unit, cy + 7f * unit, cx + 10.5f * unit,
          cy + 12f * unit, 1.4f * unit, 1.4f * unit, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.5f * unit);
      canvas.drawArc(cx + 5f * unit, cy + 3.5f * unit, cx + 9f * unit,
          cy + 7.5f * unit, 180f, 180f, false, paint);
    }
  }

  private static final class ActionIconView extends View {
    static final int SEARCH = 1;
    static final int MORE = 2;
    static final int PLUS = 3;
    private final int icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;

    ActionIconView(Context context, int icon, int color) {
      super(context);
      this.icon = icon;
      this.color = color;
      setClickable(true);
      setFocusable(true);
      TypedValue value = new TypedValue();
      if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
          value, true)) {
        setBackgroundResource(value.resourceId);
      }
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float cx = getWidth() / 2f;
      float cy = getHeight() / 2f;
      float unit = Math.min(getWidth(), getHeight()) / 44f;
      paint.setColor(color);
      paint.setStrokeWidth(2f * unit);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setStyle(Paint.Style.STROKE);
      if (icon == SEARCH) {
        canvas.drawCircle(cx - 2f * unit, cy - 2f * unit, 7f * unit, paint);
        canvas.drawLine(cx + 3f * unit, cy + 3f * unit,
            cx + 9f * unit, cy + 9f * unit, paint);
      } else if (icon == MORE) {
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy - 7f * unit, 1.7f * unit, paint);
        canvas.drawCircle(cx, cy, 1.7f * unit, paint);
        canvas.drawCircle(cx, cy + 7f * unit, 1.7f * unit, paint);
      } else if (icon == PLUS) {
        canvas.drawLine(cx - 6f * unit, cy, cx + 6f * unit, cy, paint);
        canvas.drawLine(cx, cy - 6f * unit, cx, cy + 6f * unit, paint);
      }
    }
  }
}
