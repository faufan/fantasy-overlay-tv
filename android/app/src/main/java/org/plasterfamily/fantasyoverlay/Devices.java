package org.plasterfamily.fantasyoverlay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone only: the devices running Fantasy Overlay (TVs on the Wi-Fi, plus this phone),
 * which ones are selected, and sending a command to the selected ones.
 * Network calls: background threads only.
 */
final class Devices {
    static final String LOCAL = "local";

    static final class Device {
        String name;
        String addr;   // "ip:port", or LOCAL for this phone
        boolean sel;
        String status; // last JSON status, or null if it didn't answer
        Device(String name, String addr, boolean sel) { this.name = name; this.addr = addr; this.sel = sel; }
        boolean isLocal() { return LOCAL.equals(addr); }
    }

    static final class Result {
        final String body;  // status from the first selected device that answered
        final String note;  // a short message for the person, or null
        Result(String body, String note) { this.body = body; this.note = note; }
    }

    private Devices() { }

    static List<Device> load(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        List<Device> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.devices().isEmpty() ? "[]" : prefs.devices());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Device(o.optString("name"), o.optString("addr"), o.optBoolean("sel")));
            }
        } catch (Exception ignored) { }
        // First run after updating: bring over the TV address the app already knew.
        if (list.isEmpty() && !prefs.tvAddress().isEmpty()) list.add(new Device("TV", prefs.tvAddress(), true));
        boolean hasLocal = false;
        for (Device d : list) if (d.isLocal()) hasLocal = true;
        if (!hasLocal) list.add(new Device("This phone", LOCAL, false));
        return list;
    }

    static void save(Context ctx, List<Device> list) {
        JSONArray a = new JSONArray();
        try {
            for (Device d : list) {
                JSONObject o = new JSONObject();
                o.put("name", d.name);
                o.put("addr", d.addr);
                o.put("sel", d.sel);
                a.put(o);
            }
        } catch (Exception ignored) { }
        new Prefs(ctx).setDevices(a.toString());
    }

    /** Searches the Wi-Fi, adds new devices, updates addresses that changed, and fetches each status. */
    static List<Device> refresh(Context ctx) {
        List<Device> list = load(ctx);
        if (TvClient.onLocalNetwork(ctx)) {
            for (String[] found : TvClient.discoverAll(ctx, 4000, false)) {
                Device match = null;
                for (Device d : list) if (!d.isLocal() && (d.addr.equals(found[1]) || d.name.equals(found[0]))) match = d;
                if (match == null) {
                    boolean first = true;
                    for (Device d : list) if (!d.isLocal()) first = false;
                    list.add(0, new Device(found[0], found[1], first));
                } else {
                    match.name = found[0];
                    match.addr = found[1];
                }
            }
        }
        for (Device d : list) d.status = call(ctx, d, "/status");
        save(ctx, list);
        return list;
    }

    /** One command to one device. Returns its status JSON, or null if it didn't answer. */
    static String call(Context ctx, Device d, String path) {
        if (d.isLocal()) {
            // This phone's own panel: make sure its service is running, then talk to it directly.
            String body = TvClient.get("127.0.0.1:" + Prefs.PORT, path);
            if (body != null || "/status".equals(path)) return body;
            try { OverlayService.send(ctx, OverlayService.ACTION_BOOT); } catch (Exception ignored) { }
            for (int i = 0; i < 6 && body == null; i++) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                body = TvClient.get("127.0.0.1:" + Prefs.PORT, path);
            }
            return body;
        }
        return TvClient.get(d.addr, path);
    }

    /** Sends a command to every selected device. */
    static Result sendSelected(Context ctx, String path) {
        List<Device> list = load(ctx);
        boolean home = TvClient.onLocalNetwork(ctx);
        String first = null;
        int selected = 0, failed = 0, away = 0;
        for (Device d : list) {
            if (!d.sel) continue;
            selected++;
            if (!d.isLocal() && !home) { away++; continue; }
            String body = call(ctx, d, path);
            if (body == null && !d.isLocal()) {
                // The TV may have a new address: look for it once.
                String found = TvClient.discover(ctx, 5000);
                if (found != null) {
                    d.addr = found;
                    save(ctx, list);
                    body = call(ctx, d, path);
                }
            }
            if (body == null) failed++;
            else if (first == null) first = body;
        }
        if (selected == 0) return new Result(null, "Pick a device in the app");
        if (away > 0 && first == null) return new Result(null, "TV controls work at home");
        if (failed > 0) return new Result(first, failed == selected ? "Can't reach the TV. Same Wi-Fi?" : "One device didn't answer");
        return new Result(first, null);
    }
}
