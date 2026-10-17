package app.openauth.authenticator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings of the app, including the face unlock of the code list. */
public final class SettingsActivity extends Activity {
  private static final int REQUEST_ENROLL = 81;
  private static final int COLOR_BACKGROUND = Color.rgb(248, 249, 250);
  private static final int COLOR_SURFACE = Color.WHITE;
  private static final int COLOR_TEXT = Color.rgb(32, 33, 36);
  private static final int COLOR_SECONDARY = Color.rgb(95, 99, 104);
  private static final int COLOR_PRIMARY = Color.rgb(26, 115, 232);
  private static final int COLOR_DIVIDER = Color.rgb(237, 239, 243);
  private static final int[] GRACE_OPTIONS = {0, 30, 60, 300};

  private FaceStore store;
  private Switch faceSwitch;
  private TextView graceValue;
  private View deleteRow;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    store = new FaceStore(this);
    setContentView(buildScreen());
  }

  private View buildScreen() {
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setBackgroundColor(COLOR_BACKGROUND);

    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(8), dp(12), dp(20), dp(8));
    TextView back = new TextView(this);
    back.setText("‹");
    back.setTextSize(34);
    back.setTextColor(COLOR_PRIMARY);
    back.setGravity(Gravity.CENTER);
    back.setContentDescription("Back");
    header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
    back.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { finish(); }
    });
    TextView title = text("Settings", 22, COLOR_TEXT, Typeface.BOLD);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    titleParams.leftMargin = dp(4);
    header.addView(title, titleParams);
    page.addView(header, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(18), dp(4), dp(18), dp(24));
    scroll.addView(content, new ScrollView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    content.addView(buildFaceCard());
    content.addView(buildGraceCard());
    deleteRow = buildDeleteCard();
    content.addView(deleteRow);
    content.addView(buildAboutCard());
    refresh();
    page.addView(scroll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    return page;
  }

  private View buildFaceCard() {
    LinearLayout card = card();
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(12), dp(12), dp(12));
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.addView(text("Face unlock", 15, COLOR_TEXT, Typeface.BOLD));
    TextView description = text("Ask for your face before the codes are shown", 13,
        COLOR_SECONDARY, Typeface.NORMAL);
    LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(2);
    labels.addView(description, descriptionParams);
    row.addView(labels, new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    faceSwitch = new Switch(this);
    faceSwitch.setTextOn("");
    faceSwitch.setTextOff("");
    faceSwitch.setContentDescription("Face unlock");
    row.addView(faceSwitch, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    faceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
        if (checked) {
          startEnrolment();
        } else {
          store.setEnabled(false);
          Toast.makeText(SettingsActivity.this, "Face unlock turned off", Toast.LENGTH_SHORT).show();
          refresh();
        }
      }
    });
    card.addView(row, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return card;
  }

  private View buildGraceCard() {
    LinearLayout card = card();
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setClickable(true);
    row.setFocusable(true);
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.addView(text("Lock again after", 15, COLOR_TEXT, Typeface.BOLD));
    TextView description = text("How long the app may stay open in the background", 13,
        COLOR_SECONDARY, Typeface.NORMAL);
    LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(2);
    labels.addView(description, descriptionParams);
    row.addView(labels, new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    graceValue = text("", 14, COLOR_PRIMARY, Typeface.NORMAL);
    row.addView(graceValue, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    row.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { chooseGrace(); }
    });
    card.addView(row, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return card;
  }

  private View buildDeleteCard() {
    LinearLayout card = card();
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setClickable(true);
    row.setFocusable(true);
    TextView label = text("Delete the enrolled face", 15, COLOR_PRIMARY, Typeface.NORMAL);
    row.addView(label, new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    row.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { confirmDelete(); }
    });
    card.addView(row, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return card;
  }

  private View buildAboutCard() {
    LinearLayout card = card();
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setClickable(true);
    row.setFocusable(true);
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.addView(text("How face unlock works", 15, COLOR_TEXT, Typeface.BOLD));
    TextView description = text("The face is compared on this device only", 13,
        COLOR_SECONDARY, Typeface.NORMAL);
    LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    descriptionParams.topMargin = dp(2);
    labels.addView(description, descriptionParams);
    row.addView(labels, new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    row.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View view) { showHowItWorks(); }
    });
    card.addView(row, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return card;
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(roundDrawable(COLOR_SURFACE, dp(16)));
    card.setElevation(dp(1));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.bottomMargin = dp(12);
    card.setLayoutParams(params);
    return card;
  }

  private void refresh() {
    if (faceSwitch != null) {
      faceSwitch.setOnCheckedChangeListener(null);
      faceSwitch.setChecked(store.isEnabled());
      faceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
        @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
          if (checked) {
            startEnrolment();
          } else {
            store.setEnabled(false);
            Toast.makeText(SettingsActivity.this, "Face unlock turned off", Toast.LENGTH_SHORT).show();
            refresh();
          }
        }
      });
    }
    if (graceValue != null) {
      graceValue.setText(graceLabel(store.getGraceSeconds()));
    }
    if (deleteRow != null) {
      deleteRow.setVisibility(store.loadSamples() == null ? View.GONE : View.VISIBLE);
    }
  }

  private String graceLabel(int seconds) {
    if (seconds <= 0) return "Immediately";
    if (seconds < 60) return seconds + " seconds";
    if (seconds == 60) return "1 minute";
    return (seconds / 60) + " minutes";
  }

  private void startEnrolment() {
    Intent intent = new Intent(this, FaceActivity.class);
    intent.putExtra(FaceActivity.EXTRA_MODE, FaceActivity.MODE_ENROLL);
    try {
      startActivityForResult(intent, REQUEST_ENROLL);
    } catch (RuntimeException e) {
      faceSwitch.setChecked(false);
      Toast.makeText(this, "The camera could not be opened", Toast.LENGTH_LONG).show();
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_ENROLL) return;
    if (resultCode == RESULT_OK) {
      store.setEnabled(true);
      Toast.makeText(this, "Face unlock is on", Toast.LENGTH_SHORT).show();
    } else {
      store.setEnabled(false);
    }
    refresh();
  }

  private void chooseGrace() {
    final String[] options = {"Immediately", "30 seconds", "1 minute", "5 minutes"};
    int current = store.getGraceSeconds();
    int selected = 0;
    for (int i = 0; i < GRACE_OPTIONS.length; i++) {
      if (GRACE_OPTIONS[i] == current) selected = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("Lock again after")
        .setSingleChoiceItems(options, selected, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            store.setGraceSeconds(GRACE_OPTIONS[which]);
            refresh();
            dialog.dismiss();
          }
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void confirmDelete() {
    new AlertDialog.Builder(this)
        .setTitle("Delete the enrolled face?")
        .setMessage("The face data is removed from this device and face unlock is turned off.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            store.clear();
            refresh();
            Toast.makeText(SettingsActivity.this, "The enrolled face was deleted",
                Toast.LENGTH_SHORT).show();
          }
        })
        .show();
  }

  private void showHowItWorks() {
    new AlertDialog.Builder(this)
        .setTitle("How face unlock works")
        .setMessage("The app takes a few pictures of your face with the front camera and keeps "
            + "a description of them, encrypted with the Android Keystore, on this device. "
            + "Every time the app is opened, the camera picture is compared with that "
            + "description on the device. The pictures are never stored and never sent "
            + "anywhere, and the face recognition of Android is not used.")
        .setPositiveButton("Got it", null)
        .show();
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
}
