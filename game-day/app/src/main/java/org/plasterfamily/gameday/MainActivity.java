package org.plasterfamily.gameday;

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

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.URLEncoder;

/**
 * Settings and controls. On the TV, the buttons control this TV's window.
 * On a phone, the same buttons control the TV over Wi-Fi (found automatically).
 */
public class MainActivity extends Activity {
    private boolean tv;
    private Prefs prefs;
    private TextView status, permHelp;
    private Button permBtn;
    private EditText linkField, tvField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = StreamService.isTv(this);
        prefs = new Prefs(this);
        if (tv) StreamService.start(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(40), dp(28), dp(40), dp(28));
        col.setBackgroundColor(Color.rgb(10, 14, 20));

        col.addView(text("Game Day", 30, Color.WHITE));
        status = text("", 16, Color.rgb(201, 209, 220));
        status.setPadding(0, dp(8), 0, dp(16));
        col.addView(status);

        if (tv) {
            permBtn = button("Grant overlay permission", v -> openPermission());
            col.addView(permBtn);
            permHelp = text("If that doesn't open a settings page, run this once from a computer:\n"
                    + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", 15, Color.rgb(255, 180, 107));
            permHelp.setPadding(0, dp(4), 0, dp(12));
            col.addView(permHelp);
        }

        col.addView(label(tv ? "Play a link on this TV" : "Send a link to the TV"));
        LinearLayout linkRow = row();
        linkField = new EditText(this);
        linkField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setSingleLine(true);
        linkField.setTextColor(Color.WHITE);
        linkField.setHint("YouTube, Twitch, or a video link");
        linkField.setHintTextColor(Color.rgb(94, 104, 118));
        linkRow.addView(linkField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        linkRow.addView(button("Play", v -> {
            String link = linkField.getText().toString().trim();
            if (link.isEmpty()) return;
            try { cmd("/show?u=" + URLEncoder.encode(link, "UTF-8")); } catch (Exception ignored) { }
        }));
        col.addView(linkRow);

        LinearLayout controls = row();
        controls.setPadding(0, dp(12), 0, 0);
        controls.addView(button("Show / Hide", v -> cmd("/toggle")));
        controls.addView(button("Sound", v -> cmd("/sound")));
        controls.addView(button("Move", v -> cmd("/pos?p=next")));
        controls.addView(button("Size", v -> cmd("/size?s=next")));
        col.addView(controls);

        if (!tv) {
            col.addView(label("TV address (found automatically, change only if needed)"));
            LinearLayout tvRow = row();
            tvField = new EditText(this);
            tvField.setSingleLine(true);
            tvField.setTextColor(Color.WHITE);
            tvField.setHint("Searching your Wi-Fi…");
            tvField.setHintTextColor(Color.rgb(94, 104, 118));
            tvRow.addView(tvField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tvRow.addView(button("Save", v -> { prefs.setTvAddress(tvField.getText().toString()); cmd("/status"); }));
            tvRow.addView(button("Find TV", v -> { prefs.setTvAddress(""); cmd("/status"); }));
            col.addView(tvRow);

            TextView tip = text("Tip: in YouTube or Chrome, tap Share → Game Day (TV) to send a video straight to the TV. "
                    + "Add the Game Day widget to your home screen for one-tap controls.", 15, Color.rgb(167, 176, 189));
            tip.setPadding(0, dp(20), 0, 0);
            col.addView(tip);
        } else {
            TextView tip = text("The video floats on top of whatever you're watching. Your remote keeps controlling the show underneath. "
                    + "Install Game Day on your phone too: it finds this TV by itself.", 15, Color.rgb(167, 176, 189));
            tip.setPadding(0, dp(20), 0, 0);
            col.addView(tip);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tv) {
            boolean perm = Settings.canDrawOverlays(this);
            permBtn.setVisibility(perm ? View.GONE : View.VISIBLE);
            permHelp.setVisibility(perm ? View.GONE : View.VISIBLE);
        }
        cmd("/status");
    }

    /** Sends a command to this TV (on the TV) or to the TV over Wi-Fi (on a phone), then shows the result. */
    private void cmd(String path) {
        status.setText(tv ? "Working…" : "Talking to the TV…");
        new Thread(() -> {
            String body = null, error = null;
            if (tv) {
                for (int i = 0; i < 5 && body == null; i++) {   // the service may still be starting
                    body = TvLink.get("127.0.0.1:" + Prefs.PORT, path);
                    if (body == null) try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                }
                if (body == null) error = "Game Day isn't running yet. Try again in a moment.";
            } else {
                TvLink.Result r = TvLink.send(getApplicationContext(), path);
                body = r.body;
                error = r.error;
                if (body != null) prefs.setLastStatus(body);
            }
            final String b = body, e = error;
            runOnUiThread(() -> show(b, e));
        }).start();
        if (!tv) GameDayWidget.refreshAll(this);
    }

    private void show(String body, String error) {
        StringBuilder sb = new StringBuilder();
        if (tv) {
            String ip = localIp();
            sb.append(Settings.canDrawOverlays(this) ? "Permission: granted" : "Permission: NOT granted yet");
            sb.append("\nThis TV: ").append(ip == null ? "(no network)" : ip);
        } else {
            String addr = prefs.tvAddress();
            sb.append(addr.isEmpty() ? "TV: not found yet" : "TV: " + addr);
            if (tvField != null && !tvField.hasFocus()) tvField.setText(addr);
        }
        if (error != null) {
            sb.append("\n").append(error);
        } else if (body != null) {
            try {
                JSONObject o = new JSONObject(body);
                int pi = Math.max(0, Prefs.indexOf(Prefs.POSITIONS, o.optString("pos", "bl")));
                int si = Math.max(0, Prefs.indexOf(Prefs.SIZES, o.optString("size", "m")));
                sb.append("\nWindow: ").append(o.optBoolean("visible") ? "showing" : "hidden")
                        .append(" · ").append(o.optBoolean("muted", true) ? "muted" : "sound on")
                        .append(" · ").append(Prefs.SIZE_NAMES[si]).append(", ").append(Prefs.POSITION_NAMES[pi]);
                String link = o.optString("link", "");
                if (!link.isEmpty()) sb.append("\nLast link: ").append(link);
                String err = o.optString("error", "");
                if (!err.isEmpty()) sb.append("\n").append(err);
            } catch (Exception ignored) { }
        }
        status.setText(sb.toString());
    }

    private void openPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (ActivityNotFoundException ignored) { }
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

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
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
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
