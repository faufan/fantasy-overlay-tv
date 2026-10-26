package org.plasterfamily.fantasyoverlay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phone → TV commands. Tries the saved TV address first; if it doesn't answer, searches the
 * Wi-Fi for the TV (which announces itself), saves the new address and tries again.
 * Call only from a background thread.
 */
final class TvClient {
    static final class Result {
        final String body;   // the TV's JSON status, or null
        final String error;  // a short message for the person, or null
        Result(String body, String error) { this.body = body; this.error = error; }
    }

    private TvClient() { }

    static Result send(Context ctx, String pathAndQuery) {
        Prefs prefs = new Prefs(ctx);
        String saved = prefs.tvAddress();
        if (!saved.isEmpty()) {
            String body = get(saved, pathAndQuery);
            if (body != null) return new Result(body, null);
        }
        String found = discover(ctx, 6000);
        if (found == null) {
            return new Result(null, saved.isEmpty() ? "Can't find the TV. Same Wi-Fi?" : "Can't reach the TV. Same Wi-Fi?");
        }
        prefs.setTvAddress(found);
        String body = get(found, pathAndQuery);
        return body != null ? new Result(body, null) : new Result(null, "Found the TV but it didn't answer");
    }

    static String get(String address, String pathAndQuery) {
        String host = address.contains(":") ? address : address + ":" + Prefs.PORT;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + pathAndQuery).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(3500);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String body = sb.toString();
            return body.contains("\"mode\"") ? body : null;   // it's Fantasy Overlay answering
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Is the phone on Wi-Fi (or Ethernet)? TV controls only work on the home network. */
    static boolean onLocalNetwork(Context ctx) {
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return true;
        }
    }

    /** Looks for the TV on the Wi-Fi. Returns "ip:port" or null. */
    static String discover(Context ctx, long timeoutMs) {
        java.util.List<String[]> all = discoverAll(ctx, timeoutMs, true);
        return all.isEmpty() ? null : all.get(0)[1];
    }

    /**
     * Finds every device running Fantasy Overlay on the Wi-Fi.
     * Returns a list of {name, "ip:port"}. With firstOnly, stops at the first one found.
     */
    static java.util.List<String[]> discoverAll(Context ctx, long timeoutMs, boolean firstOnly) {
        java.util.List<String[]> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Context app = ctx.getApplicationContext();
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return results;

        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("fantasy-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { lock = null; }

        CountDownLatch done = new CountDownLatch(1);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }

            @Override
            @SuppressWarnings("deprecation")
            public void onServiceFound(NsdServiceInfo info) {
                if (firstOnly && !results.isEmpty()) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int code) { }

                        @Override
                        public void onServiceResolved(NsdServiceInfo s) {
                            InetAddress host = s.getHost();
                            if (host == null) return;
                            String candidate = host.getHostAddress() + ":" + s.getPort();
                            String body = get(candidate, "/status");
                            if (body == null) return;
                            String name = s.getServiceName().replaceFirst("^FantasyOverlay-", "");
                            try { name = new org.json.JSONObject(body).optString("name", name); } catch (Exception ignored) { }
                            synchronized (results) {
                                for (String[] r : results) if (r[1].equals(candidate)) return;
                                results.add(new String[]{name, candidate});
                            }
                            if (firstOnly) done.countDown();
                        }
                    });
                } catch (Exception ignored) { }
            }
        };

        try {
            nsd.discoverServices(OverlayService.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) { }
            try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        }
        synchronized (results) { return new java.util.ArrayList<>(results); }
    }
}
