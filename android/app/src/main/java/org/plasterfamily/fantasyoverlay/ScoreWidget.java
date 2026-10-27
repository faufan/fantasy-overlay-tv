package org.plasterfamily.fantasyoverlay;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone home-screen widget in three sizes (Android 12+ switches as you resize it):
 *   small  (4x1) – logos, scores and UP / DOWN
 *   medium (4x2) – score card + mode buttons
 *   large  (4x4) – score card + your players in a game right now + mode buttons
 * Refreshes about every minute while games are on, every 15 minutes before kickoff,
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

    private static final int[] MODE_IDS = {R.id.modeFull, R.id.modeLive, R.id.modeScore, R.id.modeMini, R.id.modeAuto, R.id.modeOff};
    private static final String[] MODE_LABELS = {"Full panel", "Live players", "Score only", "Mini pill", "Score, opens on big plays", "Hidden"};

    private static final int[] TILE = {R.id.tile0, R.id.tile1, R.id.tile2, R.id.tile3, R.id.tile4, R.id.tile5, R.id.tile6, R.id.tile7};
    private static final int[] TN = {R.id.tn0, R.id.tn1, R.id.tn2, R.id.tn3, R.id.tn4, R.id.tn5, R.id.tn6, R.id.tn7};
    private static final int[] TM = {R.id.tm0, R.id.tm1, R.id.tm2, R.id.tm3, R.id.tm4, R.id.tm5, R.id.tm6, R.id.tm7};
    private static final int[] TP = {R.id.tp0, R.id.tp1, R.id.tp2, R.id.tp3, R.id.tp4, R.id.tp5, R.id.tp6, R.id.tp7};
    private static final int[] TILE_ROWS = {R.id.tileRow0, R.id.tileRow1, R.id.tileRow2, R.id.tileRow3};

    private static final int[] DOT_COLORS = {0xFF2F6FD1, 0xFFD46A1F, 0xFF2E8B4A, 0xFF8E44AD, 0xFFC0392B, 0xFF16A085, 0xFFB7950B};

    /** Points per player from the previous refresh, to light up whoever just scored. */
    private static Map<String, Double> lastPoints = new HashMap<>();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context, null, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle options) {
        // Resized: redraw so older Android versions pick a fitting layout too.
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

    /** Score first (works anywhere), then the TV command (home Wi-Fi only). */
    private static void refresh(Context context, String tvPath, PendingResult pending) {
        final Context app = context.getApplicationContext();
        final Prefs prefs = new Prefs(app);
        draw(app, parse(prefs.lastMatchup()), tvPath != null ? "Sending…" : null, false);

        IO.execute(() -> {
            JSONObject data = parse(prefs.lastMatchup());
            String note = null;
            try {
                try {
                    data = fetchMatchup(prefs);
                    prefs.setLastMatchup(data.toString());
                    draw(app, data, tvPath != null ? "Sending…" : null, true);
                } catch (Exception e) {
                    note = "Couldn't update the score";
                }
                if (tvPath != null) {
                    Devices.Result r = Devices.sendSelected(app, tvPath);
                    if (r.note != null) note = r.note;
                    syncFromTv(prefs, r.body);
                }
                draw(app, data, note, false);
            } catch (Exception e) {
                draw(app, data, note != null ? note : "Something went wrong", false);
            } finally {
                scheduleNext(app, Matchup.from(data));
                pending.finish();
            }
        });
    }

    /** Keep the phone's idea of mode/background/smart in step with what the device reports. */
    static void syncFromTv(Prefs prefs, String body) {
        if (body == null) return;
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("mode")) prefs.setMode(o.optString("mode"));
            if (o.has("bg")) prefs.setBg(o.optString("bg"));
            if (o.has("smart")) prefs.setSmart(o.optBoolean("smart"));
        } catch (Exception ignored) { }
    }

    private static PendingIntent refreshIntent(Context app) {
        Intent i = new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(app, 300, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Non-waking alarm: never wakes a sleeping phone; catches up as soon as it wakes. */
    private static void scheduleNext(Context app, Matchup m) {
        int[] ids = AppWidgetManager.getInstance(app).getAppWidgetIds(new ComponentName(app, ScoreWidget.class));
        if (ids.length == 0) return;
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        long delay = m.live ? LIVE_MS : m.pending ? PENDING_MS : IDLE_MS;
        am.setWindow(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, Math.max(10_000L, delay / 6), refreshIntent(app));
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

    // ------------------------------------------------------------------ drawing

    /**
     * @param fresh true when data was just fetched: players whose points went up since the
     *              previous fetch get highlighted in the large size.
     */
    private static void draw(Context app, JSONObject data, String note, boolean fresh) {
        Prefs prefs = new Prefs(app);
        Matchup m = Matchup.from(data);

        java.util.Set<String> hot = new java.util.HashSet<>();
        if (fresh && data != null) {
            Map<String, Double> now = new HashMap<>();
            JSONObject me = data.optJSONObject("me");
            JSONArray st = me == null ? null : me.optJSONArray("starters");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    JSONObject p = st.optJSONObject(i);
                    if (p == null) continue;
                    String key = Matchup.shortName(p.optString("name"));
                    double pts = p.optDouble("pts", 0);
                    now.put(key, pts);
                    Double before = lastPoints.get(key);
                    if (before != null && pts - before >= 2) hot.add(key);
                }
            }
            lastPoints = now;
        }

        Bitmap logoA = data == null ? null : logo(app, m.meLogo);
        Bitmap logoB = data == null ? null : logo(app, m.oppLogo);

        RemoteViews small = buildSmall(app, m, data != null, logoA, logoB);
        RemoteViews medium = buildCard(app, R.layout.widget_score, m, data != null, note, prefs, hot);
        RemoteViews large = buildCard(app, R.layout.widget_large, m, data != null, note, prefs, hot);

        RemoteViews views;
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> sizes = new HashMap<>();
            sizes.put(new SizeF(180f, 40f), small);
            sizes.put(new SizeF(180f, 130f), medium);
            sizes.put(new SizeF(180f, 280f), large);
            views = new RemoteViews(sizes);
        } else {
            views = medium;
        }

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(app, ScoreWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, views);
    }

    private static RemoteViews buildSmall(Context app, Matchup m, boolean hasData, Bitmap logoA, Bitmap logoB) {
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_small);
        v.setTextViewText(R.id.meScore, m.meScore);
        v.setTextViewText(R.id.oppScore, m.oppScore);
        v.setTextViewText(R.id.meProj, m.pending || m.live ? m.meProj : hasData ? "Final" : "");
        v.setTextViewText(R.id.oppProj, m.pending || m.live ? m.oppProj : hasData ? "Final" : "");
        v.setTextViewText(R.id.week, m.status);
        v.setTextViewText(R.id.marginPill, m.pill);
        v.setTextColor(R.id.marginPill, m.marginColor());
        v.setInt(R.id.marginPill, "setBackgroundResource", m.marginBackground());
        int ca = dotIndex(m.meName), cb = dotIndex(m.oppName);
        if (cb == ca) cb = (cb + 3) % DOT_COLORS.length;   // never the same color on both sides
        setLogo(v, R.id.logoA, R.id.abbrA, logoA, m.meAbbrev, ca);
        setLogo(v, R.id.logoB, R.id.abbrB, logoB, m.oppAbbrev, cb);
        v.setOnClickPendingIntent(R.id.scoreArea, broadcast(app, 200, new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH)));
        return v;
    }

    private static int dotIndex(String teamName) {
        return Math.abs((teamName == null ? "" : teamName).hashCode()) % DOT_COLORS.length;
    }

    private static final int[] PILLS = {R.drawable.widget_pill_0, R.drawable.widget_pill_1, R.drawable.widget_pill_2,
            R.drawable.widget_pill_3, R.drawable.widget_pill_4, R.drawable.widget_pill_5, R.drawable.widget_pill_6};

    /** Shows the team's logo if it loaded; otherwise a colored pill with the team's ESPN abbreviation. */
    private static void setLogo(RemoteViews v, int imageId, int pillId, Bitmap bmp, String abbrev, int colorIndex) {
        if (bmp != null) {
            v.setImageViewBitmap(imageId, circle(bmp));
            v.setViewVisibility(imageId, View.VISIBLE);
            v.setViewVisibility(pillId, View.GONE);
        } else {
            v.setViewVisibility(imageId, View.GONE);
            v.setViewVisibility(pillId, View.VISIBLE);
            v.setTextViewText(pillId, abbrev == null || abbrev.isEmpty() ? "–" : abbrev);
            v.setInt(pillId, "setBackgroundResource", PILLS[colorIndex % PILLS.length]);
        }
    }

    private static RemoteViews buildCard(Context app, int layout, Matchup m, boolean hasData, String note, Prefs prefs, java.util.Set<String> hot) {
        RemoteViews v = new RemoteViews(app.getPackageName(), layout);
        if (hasData) {
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

        if (layout == R.layout.widget_large) {
            int n = Math.min(TILE.length, m.liveMine.size());
            for (int i = 0; i < TILE.length; i++) {
                if (i < n) {
                    String[] p = m.liveMine.get(i);
                    v.setViewVisibility(TILE[i], View.VISIBLE);
                    v.setTextViewText(TN[i], p[0]);
                    v.setTextViewText(TM[i], p[1]);
                    v.setTextViewText(TP[i], p[2]);
                    v.setInt(TILE[i], "setBackgroundResource", hot.contains(p[0]) ? R.drawable.widget_tile_hot : R.drawable.widget_tile);
                } else {
                    v.setViewVisibility(TILE[i], View.INVISIBLE);
                }
            }
            for (int r = 0; r < TILE_ROWS.length; r++) v.setViewVisibility(TILE_ROWS[r], r * 2 < n ? View.VISIBLE : View.GONE);
            v.setTextViewText(R.id.liveCount, n > 0 ? String.valueOf(m.liveMine.size()) : "");
            v.setViewVisibility(R.id.liveNone, n == 0 ? View.VISIBLE : View.GONE);
            v.setTextViewText(R.id.liveNone, m.next);
            v.setTextViewText(R.id.liveThem, m.them);
        }

        String cur = prefs.mode();
        for (int i = 0; i < Prefs.MODES.length; i++) {
            boolean on = Prefs.MODES[i].equals(cur);
            v.setInt(MODE_IDS[i], "setBackgroundResource", on ? R.drawable.widget_btn_on : R.drawable.widget_btn);
            v.setTextColor(MODE_IDS[i], on ? Color.parseColor("#04111F") : Color.parseColor("#F3F5F8"));
            Intent intent = new Intent(app, ScoreWidget.class).setAction(ACTION_MODE).putExtra(EXTRA_MODE, Prefs.MODES[i]);
            v.setOnClickPendingIntent(MODE_IDS[i], broadcast(app, 100 + i, intent));
            if (on) v.setTextViewText(R.id.status, note != null ? note : "On the TV: " + MODE_LABELS[i]);
        }

        int bi = 0;
        for (int i = 0; i < Prefs.BGS.length; i++) if (Prefs.BGS[i].equals(prefs.bg())) bi = i;
        v.setTextViewText(R.id.bgBtn, "BG " + Prefs.BG_NAMES[bi]);
        v.setOnClickPendingIntent(R.id.bgBtn, broadcast(app, 150, new Intent(app, ScoreWidget.class).setAction(ACTION_BG)));
        v.setOnClickPendingIntent(R.id.scoreArea, broadcast(app, 200, new Intent(app, ScoreWidget.class).setAction(ACTION_REFRESH)));
        return v;
    }

    private static PendingIntent broadcast(Context app, int code, Intent intent) {
        return PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ logos

    /** Downloads a team logo once and keeps a small copy. Returns null if there's none or it can't be read. */
    /** Crops a (usually square) logo into a circle, centered, so it matches the round widget style. */
    private static Bitmap circle(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, (size - src.getWidth()) / 2f, (size - src.getHeight()) / 2f, p);
        return out;
    }

    private static Bitmap logo(Context app, String url) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(app.getCacheDir(), "logos");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".png");
        if (f.exists()) {
            Bitmap cached = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (cached != null) return cached;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null; // no downloads on the main thread
        Bitmap bmp = download(url);
        if (bmp == null && url.toLowerCase().contains(".svg") && url.contains("espncdn.com")) {
            // ESPN's built-in logos are drawings (SVG); ask ESPN's image service for a picture version.
            try {
                String path = new URL(url).getPath();
                bmp = download("https://a.espncdn.com/combiner/i?img=" + URLEncoder.encode(path, "UTF-8") + "&w=96&h=96");
            } catch (Exception ignored) { }
        }
        if (bmp == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, 72, Math.max(1, Math.round(72f * bmp.getHeight() / Math.max(1, bmp.getWidth()))), true);
        try {
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) { scaled.compress(Bitmap.CompressFormat.PNG, 100, out); }
        } catch (Exception ignored) { }
        return scaled;
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4_000_000) return null;   // not a logo
                }
                bytes = out.toByteArray();
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) return bmp;
            return renderSvg(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Draws an SVG logo (ESPN's built-in logos are SVG) into a square picture. */
    private static Bitmap renderSvg(String text) {
        if (text == null || !text.contains("<svg")) return null;
        try {
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(text);
            if (svg.getDocumentViewBox() == null) {
                float w = svg.getDocumentWidth(), h = svg.getDocumentHeight();
                if (w <= 0 || h <= 0) { w = 100; h = 100; }
                svg.setDocumentViewBox(0, 0, w, h);
            }
            svg.setDocumentWidth("100%");
            svg.setDocumentHeight("100%");
            int size = 144;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            svg.renderToCanvas(canvas, com.caverock.androidsvg.RenderOptions.create().viewPort(0, 0, size, size));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Used by the phone screen too. */
    static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
