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
import java.util.List;
import java.util.Locale;

/** Main screen: a local, offline-first TOTP authenticator. */
public final class MainActivity extends Activity {
  private static final int REQUEST_SCAN = 41;
  private static final int REQUEST_FACE = 61;
  private static final int COLOR_BACKGROUND = Color.rgb(248, 249, 250);
  private static final int COLOR_SURFACE = Color.WHITE;
  private static final int COLOR_TEXT = Color.rgb(32, 33, 36);
  private static final int COLOR_SECONDARY = Color.rgb(95, 99, 104);
  private static final int COLOR_PRIMARY = Color.rgb(26, 115, 232);
  private static final int COLOR_DIVIDER = Color.rgb(237, 239, 243);

  private final Handler handler = new Handler();
  private final List<AccountCard> visibleCards = new ArrayList<AccountCard>();
  private AccountStore store;
  private FaceStore faceStore;
  private ArrayList<Account> accounts = new ArrayList<Account>();
  private LinearLayout accountList;
  private EditText searchField;
  private String searchQuery = "";
  private boolean searchVisible;
  private FrameLayout rootView;
  private View lockOverlay;
  private TextView lockMessage;
  private boolean locked;
  private boolean faceCheckRunning;
  private long backgroundedAt;
  private String pendingUri;

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
    try {
      accounts = new ArrayList<Account>(store.load());
    } catch (RuntimeException e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
    setContentView(buildScreen());
    renderAccounts();
    if (faceStore.isEnabled()) {
      pendingUri = uriFromIntent(getIntent());
      lockApp();
    } else {
      handleIncomingIntent(getIntent());
    }
  }

  @Override protected void onResume() {
    super.onResume();
    handler.removeCallbacks(clockTick);
    handler.post(clockTick);
    if (faceStore != null && faceStore.isEnabled() && !locked && backgroundedAt > 0) {
      long grace = faceStore.getGraceSeconds() * 1000L;
      if (System.currentTimeMillis() - backgroundedAt > grace) {
        lockApp();
      }
    }
  }

  @Override protected void onPause() {
    handler.removeCallbacks(clockTick);
    backgroundedAt = System.currentTimeMillis();
    super.onPause();
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (locked) {
      pendingUri = uriFromIntent(intent);
    } else {
      handleIncomingIntent(intent);
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_FACE) {
      faceCheckRunning = false;
      boolean disabled = data != null && data.getBooleanExtra(FaceActivity.EXTRA_LOCK_DISABLED, false);
      if (resultCode == RESULT_OK || disabled) {
        unlockApp();
      } else if (locked) {
        setLockMessage("The app is still locked. Look at the camera to open it.");
      }
      return;
    }
    if (requestCode == REQUEST_SCAN && resultCode == RESULT_OK && data != null) {
      String value = data.getStringExtra(ScanActivity.EXTRA_RESULT);
      importOtpUri(value);
    }
  }

  /** Hides the codes behind the lock screen and asks for a face. */
  private void lockApp() {
    locked = true;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    showLockOverlay();
    setLockMessage("Your face is needed before the codes are shown");
    startFaceCheck();
  }

  /** Starts the face check, unless one is already running. */
  private void startFaceCheck() {
    if (faceCheckRunning || isFinishing()) return;
    faceCheckRunning = true;
    Intent intent = new Intent(this, FaceActivity.class);
    intent.putExtra(FaceActivity.EXTRA_MODE, FaceActivity.MODE_VERIFY);
    try {
      startActivityForResult(intent, REQUEST_FACE);
    } catch (RuntimeException e) {
      faceCheckRunning = false;
      setLockMessage("The camera could not be opened.");
    }
  }

  /** Shows the codes again after a successful face check. */
  private void unlockApp() {
    locked = false;
    backgroundedAt = System.currentTimeMillis();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    hideLockOverlay();
    if (pendingUri != null) {
      String uri = pendingUri;
      pendingUri = null;
      importOtpUri(uri);
    }
  }

  private void showLockOverlay() {
    if (lockOverlay != null || rootView == null) return;
    FrameLayout overlay = new FrameLayout(this);
    overlay.setBackgroundColor(COLOR_BACKGROUND);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER);
    box.setPadding(dp(24), dp(24), dp(24), dp(24));

    LockMark mark = new LockMark(this);
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(76), dp(76));
    markParams.bottomMargin = dp(18);
    box.addView(mark, markParams);

    TextView title = text("Authenticator is locked", 20, COLOR_TEXT, Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    lockMessage = text("Your face is needed before the codes are shown", 14,
        COLOR_SECONDARY, Typeface.NORMAL);
    lockMessage.setGravity(Gravity.CENTER);
    lockMessage.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    messageParams.topMargin = dp(10);
    box.addView(lockMessage, messageParams);

    TextView unlock = text("Unlock with your face", 15, Color.WHITE, Typeface.BOLD);
    unlock.setGravity(Gravity.CENTER);
    unlock.setPadding(dp(18), dp(13), dp(18), dp(13));
    unlock.setBackground(roundDrawable(COLOR_PRIMARY, dp(26)));
    unlock.setClickable(true);
    unlock.setFocusable(true);
    unlock.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { startFaceCheck(); }
    });
    LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    unlockParams.topMargin = dp(22);
    box.addView(unlock, unlockParams);
    overlay.addView(box, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    rootView.addView(overlay, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    lockOverlay = overlay;
  }

  private void hideLockOverlay() {
    if (lockOverlay == null || rootView == null) return;
    rootView.removeView(lockOverlay);
    lockOverlay = null;
    lockMessage = null;
  }

  private void setLockMessage(String message) {
    if (lockMessage != null) lockMessage.setText(message);
  }

  private void configureSystemBars() {
    Window window = getWindow();
    window.setStatusBarColor(COLOR_BACKGROUND);
    window.setNavigationBarColor(COLOR_BACKGROUND);
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
    if (android.os.Build.VERSION.SDK_INT >= 26) {
      flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    }
    window.getDecorView().setSystemUiVisibility(flags);
  }

  private View buildScreen() {
    FrameLayout root = new FrameLayout(this);
    rootView = root;
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
        renderAccounts();
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
    popup.getMenu().add(Menu.NONE, 1, 0, "Settings");
    popup.getMenu().add(Menu.NONE, 2, 1, "About");
    popup.getMenu().add(Menu.NONE, 3, 2, "How codes work");
    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
      @Override public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == 1) {
          openSettings();
          return true;
        }
        if (item.getItemId() == 2) {
          showAbout();
          return true;
        }
        if (item.getItemId() == 3) {
          showHowItWorks();
          return true;
        }
        return false;
      }
    });
    popup.show();
  }

  private void openSettings() {
    try {
      startActivity(new Intent(this, SettingsActivity.class));
    } catch (RuntimeException e) {
      Toast.makeText(this, "The settings could not be opened", Toast.LENGTH_SHORT).show();
    }
  }

  private void showAbout() {
    new AlertDialog.Builder(this)
        .setTitle("About Authenticator")
        .setMessage("An independent, offline-first authenticator for standard TOTP accounts. "
            + "Your setup keys are encrypted on this device with the Android Keystore. "
            + "The codes can be protected with a face unlock that is checked on this device. "
            + "This app is not affiliated with Google.")
        .setPositiveButton("Done", null)
        .show();
  }

  private void showHowItWorks() {
    new AlertDialog.Builder(this)
        .setTitle("About verification codes")
        .setMessage("Codes are generated on this device from your setup key and the current time. "
            + "They refresh automatically, even when you are offline. If a code is rejected, "
            + "check that your device date and time are set automatically.")
        .setPositiveButton("Got it", null)
        .show();
  }

  private void renderAccounts() {
    if (accountList == null) return;
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
        @Override public void onClick(View v) { copyCode(AccountCard.this); }
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
      TextView hint = text("Tap to copy code", 12, COLOR_SECONDARY, Typeface.NORMAL);
      view.addView(hint);
    }

    void update(long now) {
      long step = now / (account.getPeriod() * 1000L);
      if (step != lastStep) {
        lastStep = step;
        try {
          String code = Totp.code(account, now);
          int split = code.length() / 2;
          codeText.setText(code.substring(0, split) + " " + code.substring(split));
          codeText.setContentDescription(code);
        } catch (RuntimeException e) {
          codeText.setText("------");
        }
      }
      countdown.update(now);
    }
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
    String uri = uriFromIntent(intent);
    if (uri == null) return;
    importOtpUri(uri);
  }

  private String uriFromIntent(Intent intent) {
    if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
      return null;
    }
    return intent.getData().toString();
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

  /** A padlock drawn for the lock screen. */
  private final class LockMark extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LockMark(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float cx = getWidth() / 2f;
      float r = Math.min(getWidth(), getHeight()) * 0.46f;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xFFE8F0FE);
      canvas.drawCircle(cx, getHeight() / 2f, r, paint);
      float bodyLeft = cx - r * 0.46f;
      float bodyRight = cx + r * 0.46f;
      float bodyTop = getHeight() / 2f - r * 0.05f;
      float bodyBottom = getHeight() / 2f + r * 0.62f;
      paint.setColor(COLOR_PRIMARY);
      canvas.drawRoundRect(new RectF(bodyLeft, bodyTop, bodyRight, bodyBottom), r * 0.14f,
          r * 0.14f, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(r * 0.16f);
      RectF shackle = new RectF(cx - r * 0.28f, getHeight() / 2f - r * 0.58f,
          cx + r * 0.28f, getHeight() / 2f + r * 0.1f);
      canvas.drawArc(shackle, 180, 180, false, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(cx, getHeight() / 2f + r * 0.22f, r * 0.12f, paint);
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
