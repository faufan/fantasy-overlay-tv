package org.plasterfamily.fantasyoverlay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Turns the Worker's matchup JSON into the text the widget and phone screen show. */
final class Matchup {
    String week = "FANTASY", league = "", meName = "Your team", oppName = "Opponent";
    String meScore = "–", oppScore = "–", meProj = "", oppProj = "";
    String marginWord = "TIED", marginNum = "0.0", outlook = "Tap to load", leftA = "", leftB = "";
    int marginState = 0;   // 1 up, -1 down, 0 tied
    int share = 50;        // your share of projected points, for the bar
    boolean live = false;  // someone in this matchup is playing right now
    boolean pending = false; // someone hasn't played yet this week

    static String fmt(double v) {
        return String.format(Locale.US, "%.1f", Math.round(v * 10) / 10.0);
    }

    static Matchup from(JSONObject data) {
        Matchup m = new Matchup();
        if (data == null) return m;
        JSONObject me = data.optJSONObject("me");
        JSONObject opp = data.optJSONObject("opp");
        if (me == null) me = new JSONObject();
        if (opp == null) opp = new JSONObject();

        double ms = me.optDouble("score", 0), os = opp.optDouble("score", 0);
        double mp = me.optDouble("proj", 0), op = opp.optDouble("proj", 0);
        double diff = ms - os;

        m.week = "WEEK " + data.optInt("week", 0);
        m.league = data.optString("league", "");
        m.meName = me.optString("name", "Your team");
        m.oppName = opp.optString("name", "Opponent");
        m.meScore = fmt(ms);
        m.oppScore = fmt(os);
        m.meProj = "Proj " + fmt(mp);
        m.oppProj = "Proj " + fmt(op);
        m.marginState = diff > 0.05 ? 1 : diff < -0.05 ? -1 : 0;
        m.marginWord = m.marginState > 0 ? "UP" : m.marginState < 0 ? "DOWN" : "TIED";
        m.marginNum = fmt(Math.abs(diff));
        m.share = (mp + op) > 0 ? (int) Math.round(mp / (mp + op) * 100) : 50;

        int playing = me.optInt("playing", 0) + opp.optInt("playing", 0);
        int toGo = me.optInt("yetToPlay", 0) + opp.optInt("yetToPlay", 0);
        m.live = playing > 0;
        m.pending = toGo > 0;

        JSONArray starters = me.optJSONArray("starters");
        double pd = mp - op;
        if (!m.live && !m.pending && starters != null && starters.length() > 0) {
            m.outlook = diff > 0 ? "Final · You won by " + fmt(diff) : diff < 0 ? "Final · You lost by " + fmt(-diff) : "Final · Tie";
        } else {
            m.outlook = pd >= 0 ? "Projected to win by " + fmt(pd) : "Projected to lose by " + fmt(-pd);
        }
        m.leftA = me.optInt("playing", 0) + " playing · " + me.optInt("yetToPlay", 0) + " to go";
        m.leftB = opp.optInt("playing", 0) + " playing · " + opp.optInt("yetToPlay", 0) + " to go";
        return m;
    }

    int marginColor() {
        return marginState > 0 ? 0xFF72F0AA : marginState < 0 ? 0xFFFF8A8A : 0xFFD3D9E1;
    }

    int marginBackground() {
        return marginState > 0 ? R.drawable.widget_margin_up : marginState < 0 ? R.drawable.widget_margin_down : R.drawable.widget_margin_tied;
    }
}
