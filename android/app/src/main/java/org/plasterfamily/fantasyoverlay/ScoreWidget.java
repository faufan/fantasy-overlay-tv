package org.plasterfamily.fantasyoverlay;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone home-screen widget: the matchup score card plus Full / Score / Auto / Off buttons.
 * A button press tells the TV app (port 8765 on the home Wi-Fi) which mode to show,
 * then refreshes the score from the Cloudflare Worker.
 */
public class ScoreWidget extends AppWidgetProvider {
    static final String ACTION_MODE = "org.plasterfamily.fantasyoverlay.WIDGET_MODE";
    static final String ACTION_REFRESH = "org.plasterfamily.fantasyoverlay.WIDGET_REFRESH";
    static final String EXTRA_MODE = "mode";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final int[] BUTTON_IDS = {R.id.modeFull, R.id.modeScore, R.id.modeAuto, R.id.modeOff};
    private static final String[] MODE_LABELS = {"Full panel", "Score only", "Score, opens on big plays", "Hidden"};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context, null, goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_MODE.equals(action)) {
            String mode = intent.getStringExtra(EXTRA_MODE);
            if (Prefs.validMode(mode)) {
                new Prefs(context).setMode(mode);
                refresh(context, mode, goAsync());
            }
            return;
        }
        if (ACTION_REFRESH.equals(action)) {
            refresh(context, null, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    /** Ask every widget to redraw (used after settings change in the app). */
    static void refreshAll(Context context) {
        Intent i = new Intent(context, ScoreWidget.class).setAction(ACTION_REFRESH);
        context.sendBroadcast(i);
    }

    private static void refresh(Context context, String sendMode, PendingResult pending) {
        final Context app = context.getApplicationContext();
        final Prefs prefs = new Prefs(app);

        // Draw right away with the last data so the button highlight feels instant.
        draw(app, parse(prefs.lastMatchup()), sendMode != null ? "Sending to TV…" : null);

        IO.execute(() -> {
            String note = null;
            try {
                if (sendMode != null) note = sendModeToTv(prefs, sendMode);
                JSONObject data = fetchMatchup(prefs);
                prefs.setLastMatchup(data.toString());
                draw(app, data, note);
            } catch (Exception e) {
                draw(app, parse(prefs.lastMatchup()), note != null ? note : "Couldn't update the score");
            } finally {
                pending.finish();
            }
        });
    }

    /** Returns a problem message, or null when the TV accepted the change. */
    private static String sendModeToTv(Prefs prefs, String mode) {
        String tv = prefs.tvAddress();
        if (tv.isEmpty()) return "Add the TV address in the app";
        String host = tv.contains(":") ? tv : tv + ":" + Prefs.PORT;
        try {
            URL url = new URL("http://" + host + "/mode?m=" + URLEncoder.encode(mode, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200 ? null : "The TV didn't accept that";
        } catch (Exception e) {
            return "Can't reach the TV. Same Wi-Fi?";
        }
    }

    private static JSONObject fetchMatchup(Prefs prefs) throws Exception {
        Uri panel = Uri.parse(prefs.url());
        Uri.Builder b = new Uri.Builder().scheme(panel.getScheme() == null ? "https" : panel.getScheme())
                .encodedAuthority(panel.getEncodedAuthority())
                .path("/api/matchup");
        for (String k : new String[]{"key", "demo"}) {
            String v = panel.getQueryParameter(k);
            if (v != null) b.appendQueryParameter(k, v);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(b.build().toString()).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }
        JSONObject o = new JSONObject(sb.toString());
        if (o.has("error")) throw new Exception(o.optString("error"));
        return o;
    }

    private static JSONObject parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", Math.round(v * 10) / 10.0);
    }

    private static void draw(Context app, JSONObject data, String note) {
        Prefs prefs = new Prefs(app);
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_score);

        if (data != null) {
            JSONObject me = data.optJSONObject("me");
            JSONObject opp = data.optJSONObject("opp");
            if (me == null) me = new JSONObject();
            if (opp == null) opp = new JSONObject();

            double ms = me.optDouble("score", 0), os = opp.optDouble("score", 0);
            double mp = me.optDouble("proj", 0), op = opp.optDouble("proj", 0);
            double diff = ms - os;

            v.setTextViewText(R.id.week, "WEEK " + data.optInt("week", 0));
            v.setTextViewText(R.id.league, data.optString("league", ""));
            v.setTextViewText(R.id.meName, me.optString("name", "Your team"));
            v.setTextViewText(R.id.oppName, opp.optString("name", "Opponent"));
            v.setTextViewText(R.id.meScore, fmt(ms));
            v.setTextViewText(R.id.oppScore, fmt(os));
            v.setTextViewText(R.id.meProj, "Proj " + fmt(mp));
            v.setTextViewText(R.id.oppProj, "Proj " + fmt(op));

            if (diff > 0.05) {
                v.setTextViewText(R.id.marginWord, "UP");
                v.setInt(R.id.marginBox, "setBackgroundResource", R.drawable.widget_margin_up);
                v.setTextColor(R.id.marginWord, Color.parseColor("#72F0AA"));
                v.setTextColor(R.id.marginNum, Color.parseColor("#72F0AA"));
            } else if (diff < -0.05) {
                v.setTextViewText(R.id.marginWord, "DOWN");
                v.setInt(R.id.marginBox, "setBackgroundResource", R.drawable.widget_margin_down);
                v.setTextColor(R.id.marginWord, Color.parseColor("#FF8A8A"));
                v.setTextColor(R.id.marginNum, Color.parseColor("#FF8A8A"));
            } else {
                v.setTextViewText(R.id.marginWord, "TIED");
                v.setInt(R.id.marginBox, "setBackgroundResource", R.drawable.widget_margin_tied);
                v.setTextColor(R.id.marginWord, Color.parseColor("#D3D9E1"));
                v.setTextColor(R.id.marginNum, Color.parseColor("#D3D9E1"));
            }
            v.setTextViewText(R.id.marginNum, fmt(Math.abs(diff)));

            int share = (mp + op) > 0 ? (int) Math.round(mp / (mp + op) * 100) : 50;
            v.setProgressBar(R.id.bar, 100, share, false);

            int meLeft = me.optInt("playing", 0) + me.optInt("yetToPlay", 0);
            int oppLeft = opp.optInt("playing", 0) + opp.optInt("yetToPlay", 0);
            double pd = mp - op;
            String outlook;
            if (meLeft == 0 && oppLeft == 0 && me.optJSONArray("starters") != null && me.optJSONArray("starters").length() > 0) {
                outlook = diff > 0 ? "Final · You won by " + fmt(diff) : diff < 0 ? "Final · You lost by " + fmt(-diff) : "Final · Tie";
            } else {
                outlook = pd >= 0 ? "Projected to win by " + fmt(pd) : "Projected to lose by " + fmt(-pd);
            }
            v.setTextViewText(R.id.outlook, outlook);
            v.setTextViewText(R.id.leftA, me.optInt("playing", 0) + " playing · " + me.optInt("yetToPlay", 0) + " to go");
            v.setTextViewText(R.id.leftB, opp.optInt("playing", 0) + " playing · " + opp.optInt("yetToPlay", 0) + " to go");
        }

        // Mode buttons: highlight the current one and wire each to a broadcast.
        String cur = prefs.mode();
        for (int i = 0; i < Prefs.MODES.length; i++) {
            boolean on = Prefs.MODES[i].equals(cur);
            v.setInt(BUTTON_IDS[i], "setBackgroundResource", on ? R.drawable.widget_btn_on : R.drawable.widget_btn);
            v.setTextColor(BUTTON_IDS[i], on ? Color.parseColor("#04111F") : Color.parseColor("#F3F5F8"));
            Intent intent = new Intent(app, ScoreWidget.class).setAction(ACTION_MODE).putExtra(EXTRA_MODE, Prefs.MODES[i]);
            PendingIntent pi = PendingIntent.getBroadcast(app, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(BUTTON_IDS[i], pi);
            if (on) v.setTextViewText(R.id.status, note != null ? note : "On the TV: " + MODE_LABELS[i]);
        }

        // Tapping the score card refreshes it.
        Intent refresh = new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.scoreArea, PendingIntent.getBroadcast(app, 200, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(app, ScoreWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, v);
    }
}
