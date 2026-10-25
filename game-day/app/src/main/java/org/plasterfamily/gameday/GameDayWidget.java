package org.plasterfamily.gameday;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone home-screen widget: Show/Hide, Sound, Move, Size for the TV's video window. */
public class GameDayWidget extends AppWidgetProvider {
    static final String ACTION_CMD = "org.plasterfamily.gameday.WIDGET_CMD";
    static final String EXTRA_PATH = "path";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        run(context, "/status", goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_CMD.equals(intent.getAction())) {
            String path = intent.getStringExtra(EXTRA_PATH);
            run(context, path == null ? "/status" : path, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    static void refreshAll(Context context) {
        context.sendBroadcast(new Intent(context, GameDayWidget.class).setAction(ACTION_CMD).putExtra(EXTRA_PATH, "/status"));
    }

    private static void run(Context context, String path, PendingResult pending) {
        final Context app = context.getApplicationContext();
        final Prefs prefs = new Prefs(app);
        draw(app, prefs.lastStatus(), "/status".equals(path) ? "Checking the TV…" : "Sending…");
        IO.execute(() -> {
            try {
                TvLink.Result r = TvLink.send(app, path);
                if (r.body != null) prefs.setLastStatus(r.body);
                draw(app, prefs.lastStatus(), r.error);
            } catch (Exception e) {
                draw(app, prefs.lastStatus(), "Couldn't reach the TV");
            } finally {
                pending.finish();
            }
        });
    }

    private static String shortLink(String link) {
        if (link == null || link.isEmpty()) return "Nothing sent yet";
        try {
            Uri u = Uri.parse(link);
            String host = u.getHost() == null ? link : u.getHost().replaceFirst("^(www\\.|m\\.)", "");
            return host;
        } catch (Exception e) {
            return link;
        }
    }

    private static void draw(Context app, String statusJson, String note) {
        RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_gameday);

        boolean visible = false, muted = true;
        String link = "", pos = "bl", size = "m";
        try {
            JSONObject o = new JSONObject(statusJson == null || statusJson.isEmpty() ? "{}" : statusJson);
            visible = o.optBoolean("visible", false);
            muted = o.optBoolean("muted", true);
            link = o.optString("link", "");
            pos = o.optString("pos", "bl");
            size = o.optString("size", "m");
        } catch (Exception ignored) { }

        int pi = Math.max(0, Prefs.indexOf(Prefs.POSITIONS, pos));
        int si = Math.max(0, Prefs.indexOf(Prefs.SIZES, size));
        String line = visible
                ? "On TV · " + shortLink(link) + " · " + (muted ? "muted" : "sound on") + " · " + Prefs.SIZE_NAMES[si] + ", " + Prefs.POSITION_NAMES[pi]
                : "Hidden · " + shortLink(link);
        v.setTextViewText(R.id.status, note != null ? note : line);
        v.setTextViewText(R.id.btnShow, visible ? "Hide" : "Show");
        v.setTextViewText(R.id.btnSound, muted ? "Sound on" : "Mute");

        bind(app, v, R.id.btnShow, 1, "/toggle");
        bind(app, v, R.id.btnSound, 2, "/sound");
        bind(app, v, R.id.btnMove, 3, "/pos?p=next");
        bind(app, v, R.id.btnSize, 4, "/size?s=next");
        bind(app, v, R.id.header, 5, "/status");

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(app, GameDayWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, v);
    }

    private static void bind(Context app, RemoteViews v, int viewId, int code, String path) {
        Intent i = new Intent(app, GameDayWidget.class).setAction(ACTION_CMD).putExtra(EXTRA_PATH, path);
        v.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(app, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
