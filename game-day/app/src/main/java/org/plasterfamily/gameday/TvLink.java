package org.plasterfamily.gameday;

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
 * Phone → TV messages. Uses the saved TV address; if that's missing or stops answering,
 * it searches the Wi-Fi for the TV (which announces itself), saves the new address and retries.
 * Call only from a background thread.
 */
final class TvLink {
    static final class Result {
        final String body;   // the TV's JSON reply, or null
        final String error;  // a short message for the person, or null
        Result(String body, String error) { this.body = body; this.error = error; }
    }

    private TvLink() { }

    static Result send(Context ctx, String pathAndQuery) {
        Prefs prefs = new Prefs(ctx);
        String saved = prefs.tvAddress();
        if (!saved.isEmpty()) {
            String body = get(saved, pathAndQuery);
            if (body != null) return new Result(body, null);
        }
        String found = discover(ctx, 6000);
        if (found == null) {
            return new Result(null, saved.isEmpty() ? "Can't find the TV. Same Wi-Fi?" : "The TV isn't answering. Same Wi-Fi?");
        }
        prefs.setTvAddress(found);
        String body = get(found, pathAndQuery);
        return body != null ? new Result(body, null) : new Result(null, "Found the TV but it didn't answer");
    }

    /** One HTTP GET to the TV. Returns the body, or null on any failure. */
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
            // Make sure it's Game Day answering, not some other device on that address.
            return body.contains("\"gameday\"") ? body : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Looks for a TV running Game Day on the Wi-Fi. Returns "ip:port" or null. */
    static String discover(Context ctx, long timeoutMs) {
        Context app = ctx.getApplicationContext();
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;

        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("gameday-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { lock = null; }

        AtomicReference<String> found = new AtomicReference<>(null);
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
                if (found.get() != null) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int code) { }

                        @Override
                        public void onServiceResolved(NsdServiceInfo s) {
                            InetAddress host = s.getHost();
                            if (host == null || found.get() != null) return;
                            String candidate = host.getHostAddress() + ":" + s.getPort();
                            if (get(candidate, "/status") != null) {
                                found.set(candidate);
                                done.countDown();
                            }
                        }
                    });
                } catch (Exception ignored) { }
            }
        };

        try {
            nsd.discoverServices(StreamService.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) { }
            try { if (lock != null) lock.release(); } catch (Exception ignored) { }
        }
        return found.get();
    }
}
