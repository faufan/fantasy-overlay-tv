package org.plasterfamily.gameday;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Draws a 16:9 video window on top of whatever app is playing. It never takes the remote,
 * so Peacock (or anything else) underneath keeps working normally.
 * A small LAN server on port 8766 lets the phone send links and change position, size and sound.
 */
public class StreamService extends Service {
    static final String ACTION_START = "start";
    static final String NSD_TYPE = "_gameday._tcp.";

    /** Pages load with this address so YouTube and Twitch accept the embed. */
    private static final String BASE_URL = "https://plasterfamily.org/gameday/";

    static volatile StreamService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Prefs prefs;
    private FrameLayout container;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private ControlServer server;
    private NsdManager.RegistrationListener nsdListener;
    private String lastError = "";
    private String playerHtml;

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, StreamService.class).setAction(ACTION_START));
    }

    static boolean isTv(Context ctx) {
        UiModeManager ui = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        return ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = new Prefs(this);
        startInForeground();
        server = new ControlServer(this, Prefs.PORT);
        server.start();
        if (isTv(this)) announceOnNetwork();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("gameday", "Game Day", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "gameday")
                .setContentTitle("Game Day ready")
                .setContentText("Send a link from your phone")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    /** Lets phones find this TV by name, so nobody has to type an IP address. */
    private void announceOnNetwork() {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("GameDay-" + Build.MODEL.replaceAll("[^A-Za-z0-9]", ""));
            info.setServiceType(NSD_TYPE);
            info.setPort(Prefs.PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo s) { }
                @Override public void onRegistrationFailed(NsdServiceInfo s, int code) { }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int code) { }
            };
            NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {
            nsdListener = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // After a reboot the window stays hidden until a new link is sent.
        return START_STICKY;
    }

    boolean isShowing() { return container != null; }

    private String playerHtml() {
        if (playerHtml != null) return playerHtml;
        try (InputStream in = getAssets().open("player.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            playerHtml = out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            playerHtml = "<html><body style='background:#000'></body></html>";
        }
        return playerHtml;
    }

    /** Show the window (if needed) and play a link in it. */
    @SuppressLint("SetJavaScriptEnabled")
    void play(String link) {
        if (link == null || link.trim().isEmpty()) return;
        prefs.setLink(link.trim());
        if (!Settings.canDrawOverlays(this)) {
            lastError = "Overlay permission not granted";
            return;
        }
        lastError = "";
        if (container == null) {
            web = new WebView(this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            web.setBackgroundColor(Color.BLACK);
            web.setFocusable(false);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    startLink(view);
                }
            });

            container = new FrameLayout(this);
            container.addView(web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            lp = new WindowManager.LayoutParams(
                    100, 100,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            sizeWindow();
            try {
                wm.addView(container, lp);
            } catch (RuntimeException e) {
                lastError = "Could not draw the window: " + e.getMessage();
                web.destroy();
                web = null;
                container = null;
                return;
            }
            web.loadDataWithBaseURL(BASE_URL, playerHtml(), "text/html", "utf-8", null);
        } else {
            startLink(web);
        }
        prefs.setVisible(true);
    }

    private void startLink(WebView view) {
        if (view == null) return;
        String js = "window.setMuted(" + prefs.muted() + "); window.play(" + JSONObject.quote(prefs.link()) + ");";
        view.evaluateJavascript(js, null);
    }

    void hide() {
        if (container != null) {
            try { wm.removeView(container); } catch (RuntimeException ignored) { }
            container = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        prefs.setVisible(false);
    }

    void setMuted(boolean m) {
        prefs.setMuted(m);
        if (web != null) web.evaluateJavascript("window.setMuted(" + m + ");", null);
    }

    /** Work out the window's size and corner from the saved settings. */
    private void sizeWindow() {
        if (lp == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sizeIdx = Math.max(0, Prefs.indexOf(Prefs.SIZES, prefs.size()));
        float[] fractions = {0.25f, 0.33f, 0.45f};
        int w = Math.round(dm.widthPixels * fractions[sizeIdx]);
        int h = Math.round(w * 9f / 16f);
        int margin = Math.round(dm.widthPixels * 0.015f);
        String pos = prefs.position();
        lp.width = w;
        lp.height = h;
        lp.gravity = (pos.startsWith("t") ? Gravity.TOP : Gravity.BOTTOM) | (pos.endsWith("l") ? Gravity.START : Gravity.END);
        lp.x = margin;
        lp.y = margin;
    }

    private void relayout() {
        sizeWindow();
        if (container != null) {
            try { wm.updateViewLayout(container, lp); } catch (RuntimeException ignored) { }
        }
    }

    private static String next(String[] list, String cur) {
        int i = Prefs.indexOf(list, cur);
        return list[(i + 1) % list.length];
    }

    /** Runs a phone command on the main thread and returns the status as JSON. */
    String handle(String path, Map<String, String> q) {
        AtomicReference<String> out = new AtomicReference<>("{}");
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                switch (path) {
                    case "/show":
                        if (q.containsKey("u")) play(q.get("u"));
                        else if (!prefs.link().isEmpty()) play(prefs.link());
                        break;
                    case "/hide": hide(); break;
                    case "/toggle":
                        if (isShowing()) hide();
                        else if (!prefs.link().isEmpty()) play(prefs.link());
                        break;
                    case "/sound": {
                        String on = q.get("on");
                        if ("1".equals(on)) setMuted(false);
                        else if ("0".equals(on)) setMuted(true);
                        else setMuted(!prefs.muted());
                        break;
                    }
                    case "/pos": {
                        String p = q.get("p");
                        prefs.setPosition(p == null || "next".equals(p) ? next(Prefs.POSITIONS, prefs.position()) : p);
                        relayout();
                        break;
                    }
                    case "/size": {
                        String s = q.get("s");
                        prefs.setSize(s == null || "next".equals(s) ? next(Prefs.SIZES, prefs.size()) : s);
                        relayout();
                        break;
                    }
                    case "/reload":
                        if (web != null) web.loadDataWithBaseURL(BASE_URL, playerHtml(), "text/html", "utf-8", null);
                        break;
                    default: break;
                }
                out.set(statusJson());
            } finally {
                done.countDown();
            }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out.get();
    }

    String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "gameday");
            o.put("visible", isShowing());
            o.put("link", prefs.link());
            o.put("muted", prefs.muted());
            o.put("pos", prefs.position());
            o.put("size", prefs.size());
            o.put("permission", Settings.canDrawOverlays(this));
            o.put("error", lastError);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void onDestroy() {
        hide();
        if (server != null) server.shutdown();
        if (nsdListener != null) {
            try { ((NsdManager) getSystemService(Context.NSD_SERVICE)).unregisterService(nsdListener); } catch (Exception ignored) { }
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
