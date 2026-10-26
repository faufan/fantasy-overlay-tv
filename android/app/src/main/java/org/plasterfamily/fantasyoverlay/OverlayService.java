package org.plasterfamily.fantasyoverlay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the fantasy panel drawn on top of whatever app is playing.
 * The window never takes focus or touches, so the remote keeps controlling the app underneath.
 * Also runs a small LAN control server (port 8765) for Home Assistant: /show /hide /toggle /reload /set /status
 */
public class OverlayService extends Service {
    static final String ACTION_SHOW = "show";
    static final String ACTION_HIDE = "hide";
    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_RELOAD = "reload";
    static final String ACTION_APPLY = "apply";
    static final String ACTION_BOOT = "boot";
    static final String ACTION_MODE = "mode";
    static final String ACTION_APPLY_BG = "applyBg";
    static final String EXTRA_MODE = "m";

    static volatile OverlayService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Prefs prefs;
    private FrameLayout container;
    private WebView web;
    private ControlServer server;
    private String lastError = "";

    static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, OverlayService.class).setAction(action);
        ctx.startForegroundService(i);
    }

    static void sendMode(Context ctx, String mode) {
        Intent i = new Intent(ctx, OverlayService.class).setAction(ACTION_MODE).putExtra(EXTRA_MODE, mode);
        ctx.startForegroundService(i);
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

    static final String NSD_TYPE = "_fantasyoverlay._tcp.";
    private android.net.nsd.NsdManager.RegistrationListener nsdListener;

    /** The name the person gave this device in its settings ("Family Room TV"), or its model. */
    static String deviceName(Context ctx) {
        try {
            String n = Settings.Global.getString(ctx.getContentResolver(), "device_name");
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Exception ignored) { }
        return Build.MODEL;
    }

    static boolean isTv(Context ctx) {
        android.app.UiModeManager ui = (android.app.UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        return ui != null && ui.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Lets the phone find this TV by itself if its address changes. */
    private void announceOnNetwork() {
        try {
            android.net.nsd.NsdServiceInfo info = new android.net.nsd.NsdServiceInfo();
            info.setServiceName("FantasyOverlay-" + deviceName(this).replaceAll("[^A-Za-z0-9 ]", "").trim());
            info.setServiceType(NSD_TYPE);
            info.setPort(Prefs.PORT);
            nsdListener = new android.net.nsd.NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(android.net.nsd.NsdServiceInfo s) { }
                @Override public void onRegistrationFailed(android.net.nsd.NsdServiceInfo s, int code) { }
                @Override public void onServiceUnregistered(android.net.nsd.NsdServiceInfo s) { }
                @Override public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo s, int code) { }
            };
            android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) getSystemService(Context.NSD_SERVICE);
            nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {
            nsdListener = null;
        }
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("overlay", "Fantasy overlay", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "overlay")
                .setContentTitle("Fantasy overlay ready")
                .setContentText("Control from Home Assistant on port " + Prefs.PORT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_BOOT;
        switch (action) {
            case ACTION_SHOW: show(); break;
            case ACTION_HIDE: hide(); break;
            case ACTION_TOGGLE: toggle(); break;
            case ACTION_RELOAD: reload(); break;
            case ACTION_APPLY: apply(); break;
            case ACTION_BOOT: if (prefs.visible()) show(); break;
            case ACTION_MODE: setMode(intent.getStringExtra(EXTRA_MODE)); break;
            case ACTION_APPLY_BG: applyMode(true); break;
        }
        return START_STICKY;
    }

    boolean isShowing() { return container != null; }

    void toggle() { if (isShowing()) hide(); else show(); }

    @SuppressLint("SetJavaScriptEnabled")
    void show() {
        if (container != null) return;
        if (!Settings.canDrawOverlays(this)) {
            lastError = "Overlay permission not granted";
            return;
        }
        lastError = "";

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int width = Math.round(dm.widthPixels * prefs.width() / 100f);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                applyMode();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    lastError = "Page failed to load: " + error.getDescription();
                    // Usually the network isn't up yet (right after boot). Try again shortly.
                    main.postDelayed(() -> { if (web == view) view.reload(); }, 15000);
                }
            }
        });
        web.loadUrl(prefs.url());

        container = new FrameLayout(this);
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (prefs.leftSide() ? Gravity.START : Gravity.END);

        try {
            wm.addView(container, lp);
            prefs.setVisible(true);
        } catch (RuntimeException e) {
            lastError = "Could not draw overlay: " + e.getMessage();
            web.destroy();
            web = null;
            container = null;
        }
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

    void reload() {
        if (web != null) web.reload();
    }

    /** Full, score-only, auto or off. The page hides parts of itself; the window stays so switching is instant. */
    void setMode(String m) {
        if (!Prefs.validMode(m)) return;
        prefs.setMode(m);
        if (!isShowing() && !"off".equals(m)) show();
        applyMode(true);
    }

    void applyMode() { applyMode(false); }

    /**
     * Tells the page which mode to show. If the page is an older copy without modes
     * (loaded before a Worker update), reload it once so the new version takes over.
     */
    void applyMode(boolean reloadIfOld) {
        if (web == null) return;
        final WebView view = web;
        // Mode and background come from fixed lists and smart is a boolean, so they are safe in the script.
        // An older page (from before a Worker update) lacks setSmart, so it gets reloaded once.
        String js = "(typeof window.setMode === 'function' && typeof window.setBg === 'function' && typeof window.setSmart === 'function') ? "
                + "(window.setMode('" + prefs.mode() + "'), window.setBg('" + prefs.bg() + "'), window.setSmart(" + prefs.smart() + "), 'ok') : "
                + "((window.setMode && window.setMode('" + prefs.mode() + "')), 'old')";
        view.evaluateJavascript(js, result -> {
            if (reloadIfOld && "\"old\"".equals(result) && view == web) view.reload();
        });
    }

    /** Re-create the window so new width/side/url settings take effect. */
    void apply() {
        if (isShowing()) {
            hide();
            show();
        }
    }

    /** Called from the control server thread; runs the command on the main thread and returns JSON. */
    String handle(String path, Map<String, String> q) {
        AtomicReference<String> out = new AtomicReference<>("{}");
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                switch (path) {
                    case "/show": show(); break;
                    case "/hide": hide(); break;
                    case "/toggle": toggle(); break;
                    case "/reload": reload(); break;
                    case "/mode": setMode(q.get("m")); break;
                    case "/smart":
                        prefs.setSmart("1".equals(q.get("on")) || "true".equals(q.get("on")));
                        applyMode(true);
                        break;
                    case "/bg": {
                        String v = q.get("v");
                        prefs.setBg(v == null || "next".equals(v) ? Prefs.nextBg(prefs.bg()) : v);
                        applyMode(true);
                        break;
                    }
                    case "/set":
                        if (q.containsKey("url")) prefs.setUrl(q.get("url"));
                        if (q.containsKey("width")) {
                            try { prefs.setWidth(Integer.parseInt(q.get("width"))); } catch (NumberFormatException ignored) { }
                        }
                        if (q.containsKey("side")) prefs.setLeftSide("left".equalsIgnoreCase(q.get("side")));
                        apply();
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
            o.put("visible", isShowing());
            o.put("mode", prefs.mode());
            o.put("bg", prefs.bg());
            o.put("smart", prefs.smart());
            o.put("name", deviceName(this));
            o.put("app", "fantasyoverlay");
            o.put("url", prefs.url());
            o.put("width", prefs.width());
            o.put("side", prefs.leftSide() ? "left" : "right");
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
            try { ((android.net.nsd.NsdManager) getSystemService(Context.NSD_SERVICE)).unregisterService(nsdListener); } catch (Exception ignored) { }
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
