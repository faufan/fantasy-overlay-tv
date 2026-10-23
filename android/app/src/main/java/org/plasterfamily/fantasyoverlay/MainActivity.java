package org.plasterfamily.fantasyoverlay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;

/** Settings screen, navigable with the TV remote. */
public class MainActivity extends Activity {
    private Prefs prefs;
    private TextView status;
    private TextView permissionHelp;
    private Button toggleBtn, widthBtn, sideBtn, demoBtn, permBtn;
    private EditText urlField, tvField;
    private Button[] modeBtns;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        OverlayService.send(this, OverlayService.ACTION_BOOT);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(32), dp(48), dp(32));
        col.setBackgroundColor(Color.rgb(10, 14, 20));

        TextView title = text("Fantasy Overlay", 30, Color.WHITE);
        col.addView(title);

        status = text("", 16, Color.rgb(201, 209, 220));
        status.setPadding(0, dp(8), 0, dp(16));
        col.addView(status);

        permBtn = button("Grant overlay permission", v -> openPermission());
        col.addView(permBtn);
        permissionHelp = text("If that button doesn't open a settings page, run this once from a computer:\n"
                + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", 15, Color.rgb(255, 180, 107));
        permissionHelp.setPadding(0, dp(4), 0, dp(12));
        col.addView(permissionHelp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        toggleBtn = button("Show overlay", v -> { OverlayService.send(this, OverlayService.ACTION_TOGGLE); refreshSoon(); });
        widthBtn = button("", v -> cycleWidth());
        sideBtn = button("", v -> { prefs.setLeftSide(!prefs.leftSide()); applyAndRefresh(); });
        demoBtn = button("", v -> toggleDemo());
        Button reloadBtn = button("Reload page", v -> OverlayService.send(this, OverlayService.ACTION_RELOAD));
        row.addView(toggleBtn);
        row.addView(widthBtn);
        row.addView(sideBtn);
        row.addView(demoBtn);
        row.addView(reloadBtn);
        col.addView(row);

        TextView urlLabel = text("Panel page", 16, Color.rgb(167, 176, 189));
        urlLabel.setPadding(0, dp(20), 0, dp(4));
        col.addView(urlLabel);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlField = new EditText(this);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setTextColor(Color.WHITE);
        urlField.setSingleLine(true);
        urlRow.addView(urlField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        urlRow.addView(button("Save", v -> { prefs.setUrl(urlField.getText().toString().trim()); applyAndRefresh(); }));
        col.addView(urlRow);

        TextView modeLabel = text("Panel mode", 16, Color.rgb(167, 176, 189));
        modeLabel.setPadding(0, dp(20), 0, dp(4));
        col.addView(modeLabel);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] modeNames = {"Full", "Score", "Auto", "Off"};
        modeBtns = new Button[Prefs.MODES.length];
        for (int i = 0; i < Prefs.MODES.length; i++) {
            final String m = Prefs.MODES[i];
            modeBtns[i] = button(modeNames[i], v -> {
                prefs.setMode(m);
                OverlayService.sendMode(this, m);
                refreshSoon();
            });
            modeRow.addView(modeBtns[i]);
        }
        col.addView(modeRow);

        TextView tvLabel = text("Phone widget: the TV's address (shown on the TV's copy of this screen)", 16, Color.rgb(167, 176, 189));
        tvLabel.setPadding(0, dp(20), 0, dp(4));
        col.addView(tvLabel);
        LinearLayout tvRow = new LinearLayout(this);
        tvRow.setOrientation(LinearLayout.HORIZONTAL);
        tvField = new EditText(this);
        tvField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tvField.setTextColor(Color.WHITE);
        tvField.setSingleLine(true);
        tvField.setHint("e.g. 192.168.1.50");
        tvField.setHintTextColor(Color.rgb(94, 104, 118));
        tvRow.addView(tvField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvRow.addView(button("Save", v -> {
            prefs.setTvAddress(tvField.getText().toString());
            ScoreWidget.refreshAll(this);
            refreshSoon();
        }));
        col.addView(tvRow);

        TextView tip = text("Tip: once it's showing, press Home and open YouTube TV, Peacock, Plex or anything else. "
                + "The panel stays on top, and your remote still controls the show underneath.", 15, Color.rgb(167, 176, 189));
        tip.setPadding(0, dp(20), 0, 0);
        col.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        toggleBtn.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean perm = Settings.canDrawOverlays(this);
        OverlayService svc = OverlayService.instance;
        boolean showing = svc != null && svc.isShowing();

        String ip = localIp();
        status.setText((perm ? "Permission: granted" : "Permission: NOT granted yet")
                + "\nOverlay: " + (showing ? "showing" : "hidden") + " · mode: " + prefs.mode()
                + "\nHome Assistant control: " + (ip == null ? "(no network)" : "http://" + ip + ":" + Prefs.PORT + "/toggle"));

        permBtn.setVisibility(perm ? View.GONE : View.VISIBLE);
        permissionHelp.setVisibility(perm ? View.GONE : View.VISIBLE);
        toggleBtn.setText(showing ? "Hide overlay" : "Show overlay");
        widthBtn.setText("Width " + prefs.width() + "%");
        sideBtn.setText(prefs.leftSide() ? "Side: left" : "Side: right");
        demoBtn.setText(isDemo() ? "Demo data: on" : "Demo data: off");
        if (!urlField.hasFocus()) urlField.setText(prefs.url());
        if (!tvField.hasFocus()) tvField.setText(prefs.tvAddress());
        String cur = prefs.mode();
        for (int i = 0; i < modeBtns.length; i++) {
            modeBtns[i].setAlpha(Prefs.MODES[i].equals(cur) ? 1f : 0.55f);
        }
    }

    private void refreshSoon() { status.postDelayed(this::refresh, 400); }

    private void applyAndRefresh() {
        OverlayService.send(this, OverlayService.ACTION_APPLY);
        refreshSoon();
    }

    private void cycleWidth() {
        int[] steps = {20, 25, 30, 33};
        int cur = prefs.width(), next = steps[0];
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == cur) { next = steps[(i + 1) % steps.length]; break; }
        }
        prefs.setWidth(next);
        applyAndRefresh();
    }

    private boolean isDemo() { return prefs.url().contains("demo=1"); }

    private void toggleDemo() {
        String url = prefs.url();
        if (isDemo()) {
            url = url.replace("?demo=1&", "?").replace("&demo=1", "").replace("?demo=1", "");
        } else {
            url = url + (url.contains("?") ? "&" : "?") + "demo=1";
        }
        prefs.setUrl(url);
        urlField.setText(url);
        applyAndRefresh();
    }

    private void openPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                permissionHelp.requestFocus();
            }
        }
    }

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
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(12), 0);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
