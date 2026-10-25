package org.plasterfamily.fantasyoverlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings: panel URL, width, side, visibility, display mode, and (on the phone) the TV's address. */
final class Prefs {
    static final int PORT = 8765;
    static final String[] MODES = {"full", "score", "auto", "off"};

    private final SharedPreferences sp;
    private final String defaultUrl;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE);
        defaultUrl = ctx.getString(R.string.default_url);
    }

    static boolean validMode(String m) {
        if (m == null) return false;
        for (String x : MODES) if (x.equals(m)) return true;
        return false;
    }

    String url() { return sp.getString("url", defaultUrl); }
    void setUrl(String v) { sp.edit().putString("url", v).apply(); }

    /** Width of the panel as a percent of the screen width. */
    int width() { return sp.getInt("width", 25); }
    void setWidth(int v) { sp.edit().putInt("width", Math.max(10, Math.min(50, v))).apply(); }

    boolean leftSide() { return sp.getBoolean("left", false); }
    void setLeftSide(boolean v) { sp.edit().putBoolean("left", v).apply(); }

    boolean visible() { return sp.getBoolean("visible", false); }
    void setVisible(boolean v) { sp.edit().putBoolean("visible", v).apply(); }

    /** full, score, auto or off. */
    String mode() {
        String m = sp.getString("mode", "full");
        return validMode(m) ? m : "full";
    }
    void setMode(String m) { if (validMode(m)) sp.edit().putString("mode", m).apply(); }

    static final String[] BGS = {"0", "35", "75"};
    static final String[] BG_NAMES = {"Solid", "35%", "75%"};

    /** Background see-through: "0" (solid), "35" or "75" percent. */
    String bg() {
        String v = sp.getString("bg", "0");
        for (String x : BGS) if (x.equals(v)) return v;
        return "0";
    }
    void setBg(String v) { for (String x : BGS) if (x.equals(v)) { sp.edit().putString("bg", v).apply(); return; } }

    static String nextBg(String cur) {
        for (int i = 0; i < BGS.length; i++) if (BGS[i].equals(cur)) return BGS[(i + 1) % BGS.length];
        return BGS[0];
    }

    /** Phone only: the TV's address, e.g. 192.168.1.50 */
    String tvAddress() { return sp.getString("tv", ""); }
    void setTvAddress(String v) { sp.edit().putString("tv", v == null ? "" : v.trim()).apply(); }

    /** Phone only: last matchup JSON, so the widget can draw instantly. */
    String lastMatchup() { return sp.getString("lastMatchup", ""); }
    void setLastMatchup(String v) { sp.edit().putString("lastMatchup", v).apply(); }
}
