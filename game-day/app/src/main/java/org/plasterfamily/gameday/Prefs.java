package org.plasterfamily.gameday;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings. On the TV: what's playing and where. On the phone: where the TV is. */
final class Prefs {
    static final int PORT = 8766;
    static final String[] POSITIONS = {"bl", "tl", "tr", "br"};
    static final String[] POSITION_NAMES = {"bottom-left", "top-left", "top-right", "bottom-right"};
    static final String[] SIZES = {"s", "m", "l"};
    static final String[] SIZE_NAMES = {"small", "medium", "large"};

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("gameday", Context.MODE_PRIVATE);
    }

    static int indexOf(String[] list, String v) {
        for (int i = 0; i < list.length; i++) if (list[i].equals(v)) return i;
        return -1;
    }

    String link() { return sp.getString("link", ""); }
    void setLink(String v) { sp.edit().putString("link", v).apply(); }

    boolean visible() { return sp.getBoolean("visible", false); }
    void setVisible(boolean v) { sp.edit().putBoolean("visible", v).apply(); }

    boolean muted() { return sp.getBoolean("muted", true); }
    void setMuted(boolean v) { sp.edit().putBoolean("muted", v).apply(); }

    String position() {
        String p = sp.getString("pos", "bl");
        return indexOf(POSITIONS, p) >= 0 ? p : "bl";
    }
    void setPosition(String p) { if (indexOf(POSITIONS, p) >= 0) sp.edit().putString("pos", p).apply(); }

    String size() {
        String s = sp.getString("size", "m");
        return indexOf(SIZES, s) >= 0 ? s : "m";
    }
    void setSize(String s) { if (indexOf(SIZES, s) >= 0) sp.edit().putString("size", s).apply(); }

    /** Phone only: the TV's address. Filled in automatically by TvLink. */
    String tvAddress() { return sp.getString("tv", ""); }
    void setTvAddress(String v) { sp.edit().putString("tv", v == null ? "" : v.trim()).apply(); }

    /** Phone only: last status the TV reported, for the widget. */
    String lastStatus() { return sp.getString("lastStatus", ""); }
    void setLastStatus(String v) { sp.edit().putString("lastStatus", v).apply(); }
}
