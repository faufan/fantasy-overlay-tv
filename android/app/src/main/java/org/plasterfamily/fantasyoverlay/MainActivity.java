package org.plasterfamily.fantasyoverlay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.Inet4Address;

/**
 * On the TV: the overlay's settings, used with the remote.
 * On a phone: a remote for the TV — score card, panel mode, background, and settings.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 14, 20);
    private static final int MUTED = Color.rgb(154, 164, 178);
    private static final int ACCENT = Color.rgb(90, 169, 255);

    private boolean tv;
    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // TV screen
    private TextView status, permissionHelp;
    private Button toggleBtn, widthBtn, sideBtn, demoBtn, permBtn;
    private Button[] modeBtns, bgBtns;
    private EditText urlField;

    // Phone screen
    private View card;
    private TextView phoneStatus;
    private EditText tvField, phoneUrlField;
    private LinearLayout widthRow, sideRow, bgRow;
    private Button demoToggle;
    private String tvUrl = "";
    private int tvWidth = 25;
    private boolean tvLeft = false;
    private final Runnable tick = new Runnable() {
        @Override public void run() { loadScore(); main.postDelayed(this, 60_000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        tv = OverlayService.isTv(this);
        if (tv) {
            OverlayService.send(this, OverlayService.ACTION_BOOT);
            buildTvScreen();
        } else {
            buildPhoneScreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tv) {
            refreshTv();
        } else {
            tvCommand("/status");
            main.post(tick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------ TV

    private void buildTvScreen() {
        LinearLayout col = column(dp(48), dp(32));
        col.addView(text("Fantasy Overlay", 30, Color.WHITE));
        status = text("", 16, Color.rgb(201, 209, 220));
        status.setPadding(0, dp(8), 0, dp(16));
        col.addView(status);

        permBtn = button("Grant overlay permission", v -> openPermission());
        col.addView(permBtn);
        permissionHelp = text("If that button doesn't open a settings page, run this once from a computer:\n"
                + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", 15, Color.rgb(255, 180, 107));
        permissionHelp.setPadding(0, dp(4), 0, dp(12));
        col.addView(permissionHelp);

        LinearLayout row = row();
        toggleBtn = button("Show overlay", v -> { OverlayService.send(this, OverlayService.ACTION_TOGGLE); refreshTvSoon(); });
        widthBtn = button("", v -> cycleWidth());
        sideBtn = button("", v -> { prefs.setLeftSide(!prefs.leftSide()); applyTv(); });
        demoBtn = button("", v -> toggleDemo());
        row.addView(toggleBtn);
        row.addView(widthBtn);
        row.addView(sideBtn);
        row.addView(demoBtn);
        row.addView(button("Reload page", v -> OverlayService.send(this, OverlayService.ACTION_RELOAD)));
        col.addView(row);

        col.addView(label("Panel page"));
        LinearLayout urlRow = row();
        urlField = field("https://…/panel");
        urlRow.addView(urlField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        urlRow.addView(button("Save", v -> { prefs.setUrl(urlField.getText().toString().trim()); applyTv(); }));
        col.addView(urlRow);

        col.addView(label("Panel mode"));
        LinearLayout modeRow = row();
        String[] modeNames = {"Full", "Score", "Auto", "Off"};
        modeBtns = new Button[Prefs.MODES.length];
        for (int i = 0; i < Prefs.MODES.length; i++) {
            final String m = Prefs.MODES[i];
            modeBtns[i] = button(modeNames[i], v -> { prefs.setMode(m); OverlayService.sendMode(this, m); refreshTvSoon(); });
            modeRow.addView(modeBtns[i]);
        }
        col.addView(modeRow);

        col.addView(label("Background"));
        LinearLayout bgRowTv = row();
        bgBtns = new Button[Prefs.BGS.length];
        for (int i = 0; i < Prefs.BGS.length; i++) {
            final String b = Prefs.BGS[i];
            bgBtns[i] = button(Prefs.BG_NAMES[i], v -> { prefs.setBg(b); OverlayService.send(this, OverlayService.ACTION_APPLY_BG); refreshTvSoon(); });
            bgRowTv.addView(bgBtns[i]);
        }
        col.addView(bgRowTv);

        TextView tip = text("Install this app on your phone too: it finds this TV by itself and works as a remote and widget.",
                15, Color.rgb(167, 176, 189));
        tip.setPadding(0, dp(20), 0, 0);
        col.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        toggleBtn.requestFocus();
    }

    private void refreshTv() {
        boolean perm = Settings.canDrawOverlays(this);
        OverlayService svc = OverlayService.instance;
        boolean showing = svc != null && svc.isShowing();
        String ip = localIp();
        status.setText((perm ? "Permission: granted" : "Permission: NOT granted yet")
                + "\nOverlay: " + (showing ? "showing" : "hidden") + " · mode: " + prefs.mode()
                + "\nThis TV: " + (ip == null ? "(no network)" : ip + ":" + Prefs.PORT));
        permBtn.setVisibility(perm ? View.GONE : View.VISIBLE);
        permissionHelp.setVisibility(perm ? View.GONE : View.VISIBLE);
        toggleBtn.setText(showing ? "Hide overlay" : "Show overlay");
        widthBtn.setText("Width " + prefs.width() + "%");
        sideBtn.setText(prefs.leftSide() ? "Side: left" : "Side: right");
        demoBtn.setText(prefs.url().contains("demo=1") ? "Demo data: on" : "Demo data: off");
        if (!urlField.hasFocus()) urlField.setText(prefs.url());
        for (int i = 0; i < modeBtns.length; i++) modeBtns[i].setAlpha(Prefs.MODES[i].equals(prefs.mode()) ? 1f : 0.5f);
        for (int i = 0; i < bgBtns.length; i++) bgBtns[i].setAlpha(Prefs.BGS[i].equals(prefs.bg()) ? 1f : 0.5f);
    }

    private void refreshTvSoon() { main.postDelayed(this::refreshTv, 400); }

    private void applyTv() {
        OverlayService.send(this, OverlayService.ACTION_APPLY);
        refreshTvSoon();
    }

    private void cycleWidth() {
        int[] steps = {20, 25, 30, 33};
        int cur = prefs.width(), next = steps[0];
        for (int i = 0; i < steps.length; i++) if (steps[i] == cur) { next = steps[(i + 1) % steps.length]; break; }
        prefs.setWidth(next);
        applyTv();
    }

    private void toggleDemo() {
        prefs.setUrl(withDemo(prefs.url(), !prefs.url().contains("demo=1")));
        urlField.setText(prefs.url());
        applyTv();
    }

    private static String withDemo(String url, boolean on) {
        String clean = url.replace("?demo=1&", "?").replace("&demo=1", "").replace("?demo=1", "");
        return on ? clean + (clean.contains("?") ? "&" : "?") + "demo=1" : clean;
    }

    private void openPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (ActivityNotFoundException ignored) { }
        }
    }

    // ------------------------------------------------------------------ Phone

    private void buildPhoneScreen() {
        LinearLayout col = column(dp(20), dp(20));

        col.addView(text("Fantasy Overlay", 24, Color.WHITE));
        phoneStatus = text("Looking for the TV…", 14, MUTED);
        phoneStatus.setPadding(0, dp(4), 0, dp(16));
        col.addView(phoneStatus);

        // The same card as the widget: score, then Full / Score / Auto / Off.
        card = LayoutInflater.from(this).inflate(R.layout.widget_score, col, false);
        View scoreArea = card.findViewById(R.id.scoreArea);
        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) scoreArea.getLayoutParams();
        sp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        sp.weight = 0;
        scoreArea.setLayoutParams(sp);
        card.findViewById(R.id.bgBtn).setVisibility(View.GONE);
        scoreArea.setOnClickListener(v -> loadScore());
        int[] ids = {R.id.modeFull, R.id.modeScore, R.id.modeAuto, R.id.modeOff};
        for (int i = 0; i < ids.length; i++) {
            final String m = Prefs.MODES[i];
            card.findViewById(ids[i]).setOnClickListener(v -> { prefs.setMode(m); drawPhone(); tvCommand("/mode?m=" + m); });
        }
        col.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(section("Background"));
        bgRow = row();
        for (int i = 0; i < Prefs.BGS.length; i++) {
            final String b = Prefs.BGS[i];
            bgRow.addView(chip(Prefs.BG_NAMES[i], v -> { prefs.setBg(b); drawPhone(); tvCommand("/bg?v=" + b); }), weighted());
        }
        col.addView(bgRow);
        col.addView(hint("How much of the game shows through the panel. The text always stays solid."));

        col.addView(section("Panel size"));
        widthRow = row();
        for (int w : new int[]{20, 25, 30, 33}) {
            final int width = w;
            widthRow.addView(chip(w + "%", v -> { tvWidth = width; drawPhone(); tvCommand("/set?width=" + width); }), weighted());
        }
        col.addView(widthRow);

        col.addView(section("Side"));
        sideRow = row();
        sideRow.addView(chip("Left", v -> { tvLeft = true; drawPhone(); tvCommand("/set?side=left"); }), weighted());
        sideRow.addView(chip("Right", v -> { tvLeft = false; drawPhone(); tvCommand("/set?side=right"); }), weighted());
        col.addView(sideRow);

        col.addView(section("TV"));
        LinearLayout tvRow = row();
        tvField = field("Found automatically");
        tvRow.addView(tvField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvRow.addView(chip("Find TV", v -> { prefs.setTvAddress(""); tvCommand("/status"); }), wrap());
        col.addView(tvRow);
        LinearLayout tvButtons = row();
        tvButtons.setPadding(0, dp(8), 0, 0);
        tvButtons.addView(chip("Save address", v -> { prefs.setTvAddress(tvField.getText().toString()); tvCommand("/status"); }), weighted());
        tvButtons.addView(chip("Reload panel", v -> tvCommand("/reload")), weighted());
        demoToggle = chip("Demo data", v -> tvCommand("/set?url=" + ScoreWidget.encode(withDemo(tvUrl, !tvUrl.contains("demo=1")))));
        tvButtons.addView(demoToggle, weighted());
        col.addView(tvButtons);

        col.addView(section("Panel page"));
        LinearLayout urlRow = row();
        phoneUrlField = field("https://…/panel");
        urlRow.addView(phoneUrlField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        urlRow.addView(chip("Save", v -> {
            String u = phoneUrlField.getText().toString().trim();
            prefs.setUrl(u);
            tvCommand("/set?url=" + ScoreWidget.encode(u));
            loadScore();
        }), wrap());
        col.addView(urlRow);
        col.addView(hint("Add the Fantasy Overlay widget to your home screen for one-tap controls."));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);
        setContentView(scroll);
        drawPhone();
    }

    /** Send a command to the TV, then update the screen with what the TV reports. */
    private void tvCommand(String path) {
        phoneStatus.setText("Talking to the TV…");
        new Thread(() -> {
            TvClient.Result r = TvClient.send(getApplicationContext(), path);
            String body = r.body;
            ScoreWidget.syncFromTv(prefs, body);
            runOnUiThread(() -> {
                if (body != null) {
                    try {
                        JSONObject o = new JSONObject(body);
                        tvUrl = o.optString("url", tvUrl);
                        tvWidth = o.optInt("width", tvWidth);
                        tvLeft = "left".equals(o.optString("side", tvLeft ? "left" : "right"));
                        if (!tvUrl.isEmpty() && !phoneUrlField.hasFocus()) phoneUrlField.setText(tvUrl);
                        boolean visible = o.optBoolean("visible", false);
                        phoneStatus.setText("TV " + prefs.tvAddress() + " · overlay " + (visible ? "showing" : "hidden"));
                    } catch (Exception ignored) { }
                } else {
                    phoneStatus.setText(r.error);
                }
                if (!tvField.hasFocus()) tvField.setText(prefs.tvAddress());
                drawPhone();
                ScoreWidget.refreshAll(this);
            });
        }).start();
    }

    private void loadScore() {
        new Thread(() -> {
            JSONObject data = null;
            try {
                data = ScoreWidget.fetchMatchup(prefs);
                prefs.setLastMatchup(data.toString());
            } catch (Exception ignored) {
                data = ScoreWidget.parse(prefs.lastMatchup());
            }
            final Matchup m = Matchup.from(data);
            runOnUiThread(() -> bindCard(m));
        }).start();
    }

    private void bindCard(Matchup m) {
        setText(R.id.week, m.week);
        setText(R.id.league, m.league);
        setText(R.id.meName, m.meName);
        setText(R.id.oppName, m.oppName);
        setText(R.id.meScore, m.meScore);
        setText(R.id.oppScore, m.oppScore);
        setText(R.id.meProj, m.meProj);
        setText(R.id.oppProj, m.oppProj);
        setText(R.id.marginWord, m.marginWord);
        setText(R.id.marginNum, m.marginNum);
        ((TextView) card.findViewById(R.id.marginWord)).setTextColor(m.marginColor());
        ((TextView) card.findViewById(R.id.marginNum)).setTextColor(m.marginColor());
        card.findViewById(R.id.marginBox).setBackgroundResource(m.marginBackground());
        ((ProgressBar) card.findViewById(R.id.bar)).setProgress(m.share);
        setText(R.id.outlook, m.outlook);
        setText(R.id.leftA, m.leftA);
        setText(R.id.leftB, m.leftB);
    }

    private void setText(int id, String s) { ((TextView) card.findViewById(id)).setText(s); }

    /** Highlight the current choices. */
    private void drawPhone() {
        int[] ids = {R.id.modeFull, R.id.modeScore, R.id.modeAuto, R.id.modeOff};
        String[] labels = {"Full panel", "Score only", "Score, opens on big plays", "Hidden"};
        for (int i = 0; i < ids.length; i++) {
            boolean on = Prefs.MODES[i].equals(prefs.mode());
            TextView b = card.findViewById(ids[i]);
            b.setBackgroundResource(on ? R.drawable.widget_btn_on : R.drawable.widget_btn);
            b.setTextColor(on ? Color.rgb(4, 17, 31) : Color.rgb(243, 245, 248));
            if (on) setText(R.id.status, "On the TV: " + labels[i]);
        }
        for (int i = 0; i < bgRow.getChildCount(); i++) select(bgRow.getChildAt(i), Prefs.BGS[i].equals(prefs.bg()));
        int[] widths = {20, 25, 30, 33};
        for (int i = 0; i < widthRow.getChildCount(); i++) select(widthRow.getChildAt(i), widths[i] == tvWidth);
        select(sideRow.getChildAt(0), tvLeft);
        select(sideRow.getChildAt(1), !tvLeft);
        demoToggle.setText(tvUrl.contains("demo=1") ? "Demo: on" : "Demo: off");
    }

    private void select(View v, boolean on) {
        v.setBackground(pill(on ? ACCENT : Color.rgb(26, 33, 44), on ? ACCENT : Color.argb(31, 255, 255, 255)));
        ((Button) v).setTextColor(on ? Color.rgb(4, 17, 31) : Color.rgb(243, 245, 248));
    }

    // ------------------------------------------------------------------ helpers

    private String localIp() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private LinearLayout column(int padH, int padV) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padH, padV, padH, padV);
        col.setBackgroundColor(BG);
        return col;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams wrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Button chip(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(l);
        select(b, false);
        return b;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 12, MUTED);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(3), dp(22), 0, dp(8));
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 13, Color.rgb(94, 104, 118));
        t.setPadding(dp(3), dp(8), 0, 0);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(94, 104, 118));
        return e;
    }

    private TextView label(String s) {
        TextView t = text(s, 16, Color.rgb(167, 176, 189));
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
