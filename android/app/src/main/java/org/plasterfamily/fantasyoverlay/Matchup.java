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
    String meLogo = "", oppLogo = "", meAbbrev = "", oppAbbrev = "";
    String status = "FANTASY";      // small widget: "WEEK 7 · LIVE" / "WEEK 7 · FINAL"
    String pill = "TIED";           // small widget: "UP 12.1" / "WON 2.6" / "LOST 2.6"
    final java.util.List<String[]> liveMine = new java.util.ArrayList<>(); // {short name, "TEAM · Q2 4:10", points}
    String them = "", next = "";

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

        m.meLogo = me.optString("logo", "");
        m.oppLogo = opp.optString("logo", "");
        m.meAbbrev = me.optString("abbrev", "");
        m.oppAbbrev = opp.optString("abbrev", "");

        JSONArray mineArr = me.optJSONArray("starters");
        JSONArray theirArr = opp.optJSONArray("starters");
        String soonestKick = null, soonestText = null;
        java.util.List<double[]> theirLive = new java.util.ArrayList<>();
        java.util.List<String> theirNames = new java.util.ArrayList<>();
        for (int side = 0; side < 2; side++) {
            JSONArray arr = side == 0 ? mineArr : theirArr;
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String state = p.optString("state");
                if ("in".equals(state)) {
                    if (side == 0) {
                        String meta = p.optString("team") + (p.optString("detail").isEmpty() ? "" : " · " + p.optString("detail"));
                        m.liveMine.add(new String[]{shortName(p.optString("name")), meta, fmt(p.optDouble("pts", 0))});
                    } else {
                        theirLive.add(new double[]{p.optDouble("pts", 0), theirNames.size()});
                        theirNames.add(lastName(p.optString("name")));
                    }
                } else if ("pre".equals(state)) {
                    String kick = p.optString("kick", "");
                    if (!kick.isEmpty() && (soonestKick == null || kick.compareTo(soonestKick) < 0)) {
                        soonestKick = kick;
                        soonestText = p.optString("detail");
                    }
                }
            }
        }
        theirLive.sort((a, b) -> Double.compare(b[0], a[0]));
        StringBuilder t = new StringBuilder("Them: ");
        if (theirLive.isEmpty()) t.append("no one playing");
        else {
            t.append(theirLive.size()).append(" playing");
            for (int i = 0; i < Math.min(2, theirLive.size()); i++) {
                t.append(" · ").append(theirNames.get((int) theirLive.get(i)[1])).append(" ").append(fmt(theirLive.get(i)[0]));
            }
        }
        m.them = t.toString();
        m.next = soonestText == null ? "No one playing" : "No one playing · Next: " + soonestText;

        JSONArray starters = mineArr;
        double pd = mp - op;
        if (!m.live && !m.pending && starters != null && starters.length() > 0) {
            m.outlook = diff > 0 ? "Final · You won by " + fmt(diff) : diff < 0 ? "Final · You lost by " + fmt(-diff) : "Final · Tie";
        } else {
            m.outlook = pd >= 0 ? "Projected to win by " + fmt(pd) : "Projected to lose by " + fmt(-pd);
        }
        boolean done = !m.live && !m.pending && starters != null && starters.length() > 0;
        m.status = m.week + (m.live ? " · LIVE" : done ? " · FINAL" : "");
        if (done) m.pill = m.marginState > 0 ? "WON " + m.marginNum : m.marginState < 0 ? "LOST " + m.marginNum : "TIE";
        else m.pill = m.marginState == 0 ? "TIED" : m.marginWord + " " + m.marginNum;

        m.leftA = me.optInt("playing", 0) + " playing · " + me.optInt("yetToPlay", 0) + " to go";
        m.leftB = opp.optInt("playing", 0) + " playing · " + opp.optInt("yetToPlay", 0) + " to go";
        return m;
    }

    /** "Drake Maye" -> "D. Maye"; team defenses stay as they are. */
    static String shortName(String n) {
        if (n == null || n.isEmpty() || n.endsWith("D/ST")) return n == null ? "" : n;
        String[] parts = n.split(" ");
        if (parts.length < 2) return n;
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].matches("(?i)(Jr\\.?|Sr\\.?|II|III|IV|V)")) continue;
            if (rest.length() > 0) rest.append(' ');
            rest.append(parts[i]);
        }
        return parts[0].charAt(0) + ". " + (rest.length() > 0 ? rest : parts[1]);
    }

    static String lastName(String n) {
        String s = shortName(n);
        return s.matches("^.\\. .*") ? s.substring(3) : s;
    }

    int marginColor() {
        return marginState > 0 ? 0xFF72F0AA : marginState < 0 ? 0xFFFF8A8A : 0xFFD3D9E1;
    }

    int marginBackground() {
        return marginState > 0 ? R.drawable.widget_margin_up : marginState < 0 ? R.drawable.widget_margin_down : R.drawable.widget_margin_tied;
    }
}
