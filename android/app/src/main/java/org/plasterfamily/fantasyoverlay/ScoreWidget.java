package org.plasterfamily.fantasyoverlay;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone home-screen widget: the score card, Full / Score / Auto / Off, and a background button.
 * Refreshes itself about every minute while games are on, every 15 minutes before kickoff,
 * and every few hours when the week is done.
 */
public class ScoreWidget extends AppWidgetProvider {
    static final String ACTION_MODE = "org.plasterfamily.fantasyoverlay.WIDGET_MODE";
    static final String ACTION_BG = "org.plasterfamily.fantasyoverlay.WIDGET_BG";
    static final String ACTION_REFRESH = "org.plasterfamily.fantasyoverlay.WIDGET_REFRESH";
    static final String EXTRA_MODE = "mode";

    private static final long LIVE_MS = 60_000L;
    private static final long PENDING_MS = 15 * 60_000L;
    private static final long IDLE_MS = 3 * 60 * 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final int[] BUTTON_IDS = {R.id.modeFull, R.id.modeScore, R.id.modeAuto, R.id.modeOff};
    private static final String[] MODE_LABELS = {"Full panel", "Score only", "Score, opens on big plays", "Hidden"};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context, null, goAsync());
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(refreshIntent(context.getApplicationContext()));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Prefs prefs = new Prefs(context);
        if (ACTION_MODE.equals(action)) {
            String mode = intent.getStringExtra(EXTRA_MODE);
            if (Prefs.validMode(mode)) {
                prefs.setMode(mode);
                refresh(context, "/mode?m=" + mode, goAsync());
            }
            return;
        }
        if (ACTION_BG.equals(action)) {
            String next = Prefs.nextBg(prefs.bg());
            prefs.setBg(next);
            refresh(context, "/bg?v=" + next, goAsync());
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
        context.sendBroadcast(new Intent(context, ScoreWidget.class).setAction(ACTION_REFRESH));
    }

    private static void refresh(Context context, String tvPath, PendingResult pending) {
        final Context app = context.getApplicationContext();
        final Prefs prefs = new Prefs(app);
        draw(app, parse(prefs.lastMatchup()), tvPath != null ? "Sending to TV…" : null);

        IO.execute(() -> {
            String note = null;
            JSONObject data = null;
            try {
                if (tvPath != null) {
                    TvClient.Result r = TvClient.send(app, tvPath);
                    note = r.error;
                    syncFromTv(prefs, r.body);
                }
                data = fetchMatchup(prefs);
                prefs.setLastMatchup(data.toString());
                draw(app, data, note);
            } catch (Exception e) {
                data = parse(prefs.lastMatchup());
                draw(app, data, note != null ? note : "Couldn't update the score");
            } finally {
                scheduleNext(app, Matchup.from(data));
                pending.finish();
            }
        });
    }

    /** Keep the phone's idea of mode/background in step with what the TV reports. */
    static void syncFromTv(Prefs prefs, String body) {
        if (body == null) return;
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("mode")) prefs.setMode(o.optString("mode"));
            if (o.has("bg")) prefs.setBg(o.optString("bg"));
        } catch (Exception ignored) { }
    }

    private static PendingIntent refreshIntent(Context app) {
        Intent i = new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(app, 300, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Schedule the next refresh. Uses a non-waking alarm, so it never wakes a sleeping phone;
     * if the phone was asleep, the refresh happens as soon as it wakes up.
     */
    private static void scheduleNext(Context app, Matchup m) {
        int[] ids = AppWidgetManager.getInstance(app).getAppWidgetIds(new ComponentName(app, ScoreWidget.class));
        if (ids.length == 0) return;
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        long delay = m.live ? LIVE_MS : m.pending ? PENDING_MS : IDLE_MS;
        long at = SystemClock.elapsedRealtime() + delay;
        am.setWindow(AlarmManager.ELAPSED_REALTIME, at, Math.max(10_000L, delay / 6), refreshIntent(app));
    }

    static JSONObject fetchMatchup(Prefs prefs) throws Exception {
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

    static JSONObject parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    private static void draw(Context app, JSONObject data, String note) {
        Prefs prefs = new Prefs(app);
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_score);

        if (data != null) {
            Matchup m = Matchup.from(data);
            v.setTextViewText(R.id.week, m.week);
            v.setTextViewText(R.id.league, m.league);
            v.setTextViewText(R.id.meName, m.meName);
            v.setTextViewText(R.id.oppName, m.oppName);
            v.setTextViewText(R.id.meScore, m.meScore);
            v.setTextViewText(R.id.oppScore, m.oppScore);
            v.setTextViewText(R.id.meProj, m.meProj);
            v.setTextViewText(R.id.oppProj, m.oppProj);
            v.setTextViewText(R.id.marginWord, m.marginWord);
            v.setTextViewText(R.id.marginNum, m.marginNum);
            v.setTextColor(R.id.marginWord, m.marginColor());
            v.setTextColor(R.id.marginNum, m.marginColor());
            v.setInt(R.id.marginBox, "setBackgroundResource", m.marginBackground());
            v.setProgressBar(R.id.bar, 100, m.share, false);
            v.setTextViewText(R.id.outlook, m.outlook);
            v.setTextViewText(R.id.leftA, m.leftA);
            v.setTextViewText(R.id.leftB, m.leftB);
        }

        String cur = prefs.mode();
        for (int i = 0; i < Prefs.MODES.length; i++) {
            boolean on = Prefs.MODES[i].equals(cur);
            v.setInt(BUTTON_IDS[i], "setBackgroundResource", on ? R.drawable.widget_btn_on : R.drawable.widget_btn);
            v.setTextColor(BUTTON_IDS[i], on ? Color.parseColor("#04111F") : Color.parseColor("#F3F5F8"));
            Intent intent = new Intent(app, ScoreWidget.class).setAction(ACTION_MODE).putExtra(EXTRA_MODE, Prefs.MODES[i]);
            v.setOnClickPendingIntent(BUTTON_IDS[i], PendingIntent.getBroadcast(app, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (on) v.setTextViewText(R.id.status, note != null ? note : "On the TV: " + MODE_LABELS[i]);
        }

        int bi = 0;
        for (int i = 0; i < Prefs.BGS.length; i++) if (Prefs.BGS[i].equals(prefs.bg())) bi = i;
        v.setTextViewText(R.id.bgBtn, "BG " + Prefs.BG_NAMES[bi]);
        v.setOnClickPendingIntent(R.id.bgBtn, PendingIntent.getBroadcast(app, 150,
                new Intent(app, ScoreWidget.class).setAction(ACTION_BG),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        v.setOnClickPendingIntent(R.id.scoreArea, PendingIntent.getBroadcast(app, 200,
                new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(app, ScoreWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, v);
    }

    /** Used by the phone screen too. */
    static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
