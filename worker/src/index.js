// Fantasy panel Worker: serves the TV panel page and a trimmed ESPN matchup API.
//
//   /panel                 the panel page (the TV overlay loads this)
//   /api/matchup           your live matchup as compact JSON
//   add ?demo=1            sample data that changes every refresh (no ESPN needed)
//   add ?key=...           required on every request when ACCESS_KEY is set
//
// Config (wrangler.toml vars / secrets): LEAGUE_ID, TEAM_ID, SEASON (optional),
// ESPN_S2 + SWID (secrets, private leagues only), ACCESS_KEY (secret, optional).

import PANEL_HTML from "./panel.html";
import WATCH_HTML from "./watch.html";

const ESPN_HOST = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl";
const NFL_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard";
const CACHE_MS = 15000;
const TIME_ZONE = "America/New_York";

const SLOT = { 0: "QB", 1: "TQB", 2: "RB", 3: "RB/WR", 4: "WR", 5: "WR/TE", 6: "TE", 7: "OP", 16: "D/ST", 17: "K", 23: "FLEX" };
const SLOT_ORDER = [0, 1, 2, 3, 4, 5, 6, 23, 7, 16, 17];
const BENCH_SLOTS = new Set([20, 21, 24, 25]);
const POS = { 1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "D/ST" };
const PRO_TEAM = {
  1: "ATL", 2: "BUF", 3: "CHI", 4: "CIN", 5: "CLE", 6: "DAL", 7: "DEN", 8: "DET", 9: "GB", 10: "TEN",
  11: "IND", 12: "KC", 13: "LV", 14: "LAR", 15: "MIA", 16: "MIN", 17: "NE", 18: "NO", 19: "NYG", 20: "NYJ",
  21: "PHI", 22: "ARI", 23: "PIT", 24: "LAC", 25: "SF", 26: "SEA", 27: "TB", 28: "WSH", 29: "CAR", 30: "JAX",
  33: "BAL", 34: "HOU",
};

let memo = { at: 0, data: null };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (env.ACCESS_KEY && url.searchParams.get("key") !== env.ACCESS_KEY) {
      return new Response("Forbidden", { status: 403 });
    }

    if (url.pathname === "/panel") {
      return new Response(PANEL_HTML, {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
      });
    }

    if (url.pathname === "/" || url.pathname === "/watch") {
      return new Response(WATCH_HTML, {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
      });
    }

    if (url.pathname === "/logo") {
      return logoProxy(url.searchParams.get("u"), env, url.searchParams.get("debug") === "1");
    }

    if (url.pathname === "/api/matchup") {
      try {
        const data = url.searchParams.get("demo") ? demoData() : withLogoProxy(await getMatchup(env), url, env);
        return json(data);
      } catch (err) {
        return json({ error: String(err && err.message ? err.message : err) }, 502);
      }
    }

    return new Response("Not found", { status: 404 });
  },
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", "access-control-allow-origin": "*" },
  });
}

function currentSeason(env) {
  if (env.SEASON) return Number(env.SEASON);
  const d = new Date();
  // The NFL season that starts in September runs into January/February of the next year.
  return d.getUTCMonth() < 2 ? d.getUTCFullYear() - 1 : d.getUTCFullYear();
}

async function espn(env, query, filter) {
  if (!env.LEAGUE_ID || env.LEAGUE_ID === "CHANGE_ME") throw new Error("LEAGUE_ID is not set");
  const url = `${ESPN_HOST}/seasons/${currentSeason(env)}/segments/0/leagues/${env.LEAGUE_ID}?${query}`;
  const headers = { accept: "application/json" };
  if (env.ESPN_S2 && env.SWID) headers.cookie = `espn_s2=${env.ESPN_S2}; SWID=${env.SWID}`;
  if (filter) headers["x-fantasy-filter"] = JSON.stringify(filter);
  const res = await fetch(url, { headers });
  if (res.status === 401 || res.status === 403) throw new Error("ESPN refused access: private league needs ESPN_S2 and SWID");
  if (!res.ok) throw new Error(`ESPN returned ${res.status}`);
  const text = await res.text();
  if (text.trim().startsWith("<")) throw new Error("ESPN returned a login page: check ESPN_S2 and SWID");
  return JSON.parse(text);
}

async function nflGames() {
  const map = {};
  try {
    const res = await fetch(NFL_SCOREBOARD);
    if (!res.ok) return map;
    const data = await res.json();
    for (const ev of data.events || []) {
      const comp = (ev.competitions || [])[0];
      if (!comp) continue;
      const home = comp.competitors.find((c) => c.homeAway === "home");
      const away = comp.competitors.find((c) => c.homeAway === "away");
      if (!home || !away) continue;
      const state = comp.status?.type?.state || ev.status?.type?.state || "pre";
      let detail = comp.status?.type?.shortDetail || ev.status?.type?.shortDetail || "";
      if (state === "pre" && ev.date) detail = shortKick(ev.date) || detail;
      const a = away.team.abbreviation, h = home.team.abbreviation;
      const scoreLine = state === "pre" ? `${a} @ ${h}` : `${a} ${away.score} @ ${h} ${home.score}`;

      // Red zone: which team has the ball inside the 20, and the down and distance.
      const sit = comp.situation || {};
      const byId = { [home.team.id]: h, [away.team.id]: a };
      const redZone = state === "in" && sit.isRedZone ? byId[sit.possession] || null : null;
      const down = redZone ? sit.downDistanceText || sit.shortDownDistanceText || "" : "";

      const info = { state, detail, line: scoreLine, kick: ev.date || null, redZone, down };
      map[a] = info;
      map[h] = info;
    }
  } catch (_) {
    // Game status is a nice-to-have; fantasy points still work without it.
  }
  return map;
}

// "Sun 1p", "Mon 8:15p", "Sun 9:30a"
function shortKick(iso) {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      weekday: "short", hour: "numeric", minute: "2-digit", hour12: true, timeZone: TIME_ZONE,
    }).formatToParts(new Date(iso));
    const get = (t) => (parts.find((p) => p.type === t) || {}).value || "";
    const min = get("minute");
    const ap = get("dayPeriod").toLowerCase().startsWith("p") ? "p" : "a";
    return `${get("weekday")} ${get("hour")}${min === "00" ? "" : ":" + min}${ap}`;
  } catch (_) {
    return "";
  }
}

function teamName(t) {
  if (!t) return "Opponent";
  return t.name || [t.location, t.nickname].filter(Boolean).join(" ") || t.abbrev || `Team ${t.id}`;
}

function statFor(player, scoringPeriodId, source) {
  const s = (player.stats || []).find((x) => x.scoringPeriodId === scoringPeriodId && x.statSourceId === source);
  return s && typeof s.appliedTotal === "number" ? s.appliedTotal : null;
}

function buildSide(side, teamsById, sp, games) {
  if (!side) return null;
  const roster = side.rosterForCurrentScoringPeriod || side.rosterForMatchupPeriod || { entries: [] };
  const starters = [];
  for (const e of roster.entries || []) {
    if (BENCH_SLOTS.has(e.lineupSlotId)) continue;
    const ppe = e.playerPoolEntry || {};
    const p = ppe.player || {};
    const team = PRO_TEAM[p.proTeamId] || "";
    const game = games[team] || null;
    const actual = typeof ppe.appliedStatTotal === "number" ? ppe.appliedStatTotal : statFor(p, sp, 0) ?? 0;
    const proj = statFor(p, sp, 1);
    starters.push({
      slotId: e.lineupSlotId,
      slot: SLOT[e.lineupSlotId] || POS[p.defaultPositionId] || "",
      name: p.fullName || "Empty",
      team,
      pts: round(actual),
      proj: proj == null ? null : round(proj),
      injury: p.injuryStatus && p.injuryStatus !== "ACTIVE" ? p.injuryStatus : null,
      state: game ? game.state : team ? "bye" : "pre",
      detail: game ? game.detail : team ? "Bye" : "",
      game: game ? game.line : "",
      kick: game ? game.kick : null,
      // Offensive players whose team has the ball inside the 20.
      redZone: game && game.redZone === team && p.defaultPositionId !== 16 ? game.down || "In the red zone" : null,
    });
  }
  starters.sort((a, b) => order(a.slotId) - order(b.slotId));

  const sum = (f) => round(starters.reduce((t, p) => t + (f(p) || 0), 0));
  const score = num(side.totalPointsLive) ?? num(roster.appliedStatTotal) ?? num(side.totalPoints) ?? sum((p) => p.pts);
  const proj =
    num(side.totalProjectedPointsLive) ??
    sum((p) => (p.state === "post" || p.state === "bye" || p.proj == null ? p.pts : Math.max(p.pts, p.proj)));

  return {
    name: teamName(teamsById[side.teamId]),
    abbrev: teamsById[side.teamId]?.abbrev || "",
    // The team's ESPN logo (built-in or uploaded by the owner); empty when none is set.
    logo: typeof teamsById[side.teamId]?.logo === "string" ? teamsById[side.teamId].logo : "",
    score: round(score),
    proj: round(proj),
    playing: starters.filter((p) => p.state === "in").length,
    yetToPlay: starters.filter((p) => p.state === "pre").length,
    starters,
  };
}

function order(slotId) {
  const i = SLOT_ORDER.indexOf(slotId);
  return i === -1 ? 50 : i;
}
function num(v) {
  return typeof v === "number" && isFinite(v) ? v : null;
}
function round(v) {
  return Math.round((v || 0) * 10) / 10;
}

// ESPN only serves uploaded team logos to people signed in to the league, so the Worker
// fetches them with the league's ESPN sign-in and passes the picture along.
function withLogoProxy(data, url, env) {
  const wrap = (logo) => {
    if (!logo) return "";
    const key = env.ACCESS_KEY ? `&key=${encodeURIComponent(env.ACCESS_KEY)}` : "";
    return `${url.origin}/logo?u=${encodeURIComponent(logo)}${key}`;
  };
  return {
    ...data,
    me: data.me ? { ...data.me, logo: wrap(data.me.logo), espnLogo: data.me.logo } : data.me,
    opp: data.opp ? { ...data.opp, logo: wrap(data.opp.logo), espnLogo: data.opp.logo } : data.opp,
  };
}

async function logoProxy(raw, env, debug) {
  let target;
  try {
    target = new URL(raw || "");
  } catch (_) {
    return new Response("Bad logo address", { status: 400 });
  }
  // Only ESPN addresses, so this can't be used to fetch anything else.
  const host = target.hostname;
  const espn = host === "espn.com" || host.endsWith(".espn.com") || host.endsWith(".espncdn.com");
  if (target.protocol !== "https:" || !espn) return new Response("Not an ESPN logo", { status: 400 });

  const cache = caches.default;
  const cacheKey = new Request("https://logo-cache.local/" + encodeURIComponent(target.href));
  try {
    const hit = debug ? null : await cache.match(cacheKey);
    if (hit) return hit;
  } catch (_) { /* cache not available on workers.dev; fine */ }

  // ESPN's image server checks that the request looks like a browser on ESPN's site,
  // so try a few browser-style requests, with and without the league sign-in.
  const browser = {
    accept: "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
    "user-agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36",
    referer: "https://fantasy.espn.com/",
    origin: "https://fantasy.espn.com",
    "sec-fetch-dest": "image",
    "sec-fetch-mode": "no-cors",
    "sec-fetch-site": "same-site",
  };
  const cookie = env.ESPN_S2 && env.SWID ? `espn_s2=${env.ESPN_S2}; SWID=${env.SWID}` : null;
  const tries = [
    { name: "browser + sign-in", headers: cookie ? { ...browser, cookie } : null },
    { name: "browser", headers: browser },
    { name: "sign-in only", headers: cookie ? { cookie } : null },
    { name: "plain", headers: {} },
  ].filter((t) => t.headers);

  const report = [];
  let res = null;
  for (const t of tries) {
    try {
      const r = await fetch(target.href, { headers: t.headers, redirect: "follow" });
      const type = r.headers.get("content-type") || "";
      report.push(`${t.name}: ${r.status} ${type}`);
      if (r.ok && (/^image\//i.test(type) || /svg/i.test(type))) { res = r; break; }
    } catch (e) {
      report.push(`${t.name}: failed (${e.message})`);
    }
  }
  if (debug) return new Response(report.join("\n") + "\n", { headers: { "content-type": "text/plain" } });
  if (!res) return new Response("ESPN wouldn't send the logo:\n" + report.join("\n"), { status: 502 });
  const type = res.headers.get("content-type") || "image/png";

  const out = new Response(res.body, {
    headers: { "content-type": type, "cache-control": "public, max-age=86400", "access-control-allow-origin": "*" },
  });
  try { await cache.put(cacheKey, out.clone()); } catch (_) { }
  return out;
}

async function getMatchup(env) {
  if (memo.data && Date.now() - memo.at < CACHE_MS) return memo.data;
  const teamId = Number(env.TEAM_ID);
  if (!teamId) throw new Error("TEAM_ID is not set");

  const base = await espn(env, "view=mSettings&view=mTeam&view=mStatus");
  const sp = base.scoringPeriodId;
  const mp = base.status?.currentMatchupPeriod || sp;
  const teamsById = Object.fromEntries((base.teams || []).map((t) => [t.id, t]));

  const [box, games] = await Promise.all([
    espn(env, `scoringPeriodId=${sp}&view=mMatchupScore&view=mLiveScoring&view=mScoreboard&view=mBoxscore`, {
      schedule: { filterMatchupPeriodIds: { value: [mp] } },
    }),
    nflGames(),
  ]);

  const matchup = (box.schedule || []).find(
    (m) => m.matchupPeriodId === mp && (m.home?.teamId === teamId || m.away?.teamId === teamId)
  );
  if (!matchup) throw new Error(`No matchup found for team ${teamId} in week ${sp}`);

  const mine = matchup.home?.teamId === teamId ? matchup.home : matchup.away;
  const theirs = matchup.home?.teamId === teamId ? matchup.away : matchup.home;

  const data = {
    league: base.settings?.name || "ESPN Fantasy",
    week: sp,
    updated: new Date().toISOString(),
    me: buildSide(mine, teamsById, sp, games),
    opp: buildSide(theirs, teamsById, sp, games),
  };
  memo = { at: Date.now(), data };
  return data;
}

// Sample data for testing the overlay before the league is configured.
// Points creep up on each request so the scoring alerts can be seen.
let demoTick = 0;
function demoData() {
  demoTick++;
  const bump = (base, every, amt) => round(base + Math.floor(demoTick / every) * amt);
  const K1 = new Date(Date.now() + 3 * 3600e3).toISOString();
  const K2 = new Date(Date.now() + 7 * 3600e3).toISOString();
  const starters = [
    { slot: "QB", name: "Marcus Hale", team: "BUF", pts: bump(18.4, 3, 4.2), proj: 21.0, state: "in", detail: "3rd 6:12", game: "MIA 14 @ BUF 21" },
    { slot: "RB", name: "Dante Reyes", team: "NYG", pts: bump(12.7, 5, 6.4), proj: 15.2, state: "in", detail: "2nd 2:05", game: "NYG 10 @ NO 17" },
    { slot: "RB", name: "Theo Whitlock", team: "DET", pts: 9.1, proj: 11.0, state: "post", detail: "Final", game: "TB 20 @ DET 27" },
    { slot: "WR", name: "Jalen Brooks", team: "NO", pts: bump(14.3, 4, 8.2), proj: 16.0, state: "in", redZone: "2nd & 6 at NYG 11", detail: "2nd 2:05", game: "NYG 10 @ NO 17" },
    { slot: "WR", name: "Cam Ellison", team: "KC", pts: 0, proj: 13.8, state: "pre", kick: K1, detail: "Sun 4:25p", game: "LV @ KC" },
    { slot: "TE", name: "Owen Sato", team: "SF", pts: 0, proj: 9.6, state: "pre", kick: K2, detail: "Sun 8:20p", game: "SF @ SEA" },
    { slot: "FLEX", name: "Rico Banks", team: "NYG", pts: bump(3.2, 6, 1.5), proj: 10.0, state: "in", detail: "2nd 2:05", game: "NYG 10 @ NO 17", injury: "QUESTIONABLE" },
    { slot: "D/ST", name: "Saints D/ST", team: "NO", pts: 4.0, proj: 7.0, state: "in", detail: "2nd 2:05", game: "NYG 10 @ NO 17" },
    { slot: "K", name: "Eli Marsh", team: "BUF", pts: bump(5.0, 7, 3), proj: 8.0, state: "in", detail: "3rd 6:12", game: "MIA 14 @ BUF 21" },
  ];
  const oppStarters = [
    { slot: "QB", name: "Tyler Voss", team: "PHI", pts: bump(12.1, 4, 4.0), proj: 19.5, state: "in", detail: "1st 4:40", game: "DAL 3 @ PHI 7" },
    { slot: "RB", name: "Andre Pike", team: "DAL", pts: bump(6.2, 5, 6.1), proj: 14.0, state: "in", redZone: demoTick % 3 === 0 ? "1st & Goal at PHI 4" : null, detail: "1st 4:40", game: "DAL 3 @ PHI 7" },
    { slot: "RB", name: "Nate Coleman", team: "TB", pts: 11.4, proj: 12.0, state: "post", detail: "Final", game: "TB 20 @ DET 27" },
    { slot: "WR", name: "Malik Shaw", team: "LV", pts: 0, proj: 12.5, state: "pre", kick: K1, detail: "Sun 4:25p", game: "LV @ KC" },
    { slot: "WR", name: "Devin Ortiz", team: "MIA", pts: 8.3, proj: 13.1, state: "in", detail: "3rd 6:12", game: "MIA 14 @ BUF 21" },
    { slot: "TE", name: "Grant Weller", team: "SEA", pts: 0, proj: 8.9, state: "pre", kick: K2, detail: "Sun 8:20p", game: "SF @ SEA" },
    { slot: "FLEX", name: "Corey Bell", team: "TB", pts: 7.8, proj: 9.0, state: "post", detail: "Final", game: "TB 20 @ DET 27" },
    { slot: "D/ST", name: "Eagles D/ST", team: "PHI", pts: 3.0, proj: 6.0, state: "in", detail: "1st 4:40", game: "DAL 3 @ PHI 7" },
    { slot: "K", name: "Luis Moreno", team: "SEA", pts: 0, proj: 8.0, state: "pre", kick: K2, detail: "Sun 8:20p", game: "SF @ SEA" },
  ];
  const sum = (list) => round(list.reduce((t, p) => t + p.pts, 0));
  const proj = (list) => round(list.reduce((t, p) => t + (p.state === "post" ? p.pts : Math.max(p.pts, p.proj)), 0));
  const count = (list, s) => list.filter((p) => p.state === s).length;
  return {
    league: "Demo League",
    week: 7,
    demo: true,
    updated: new Date().toISOString(),
    me: { name: "Your Team", abbrev: "YOU", score: sum(starters), proj: proj(starters), playing: count(starters, "in"), yetToPlay: count(starters, "pre"), starters },
    opp: { name: "Opponent", abbrev: "OPP", score: sum(oppStarters), proj: proj(oppStarters), playing: count(oppStarters, "in"), yetToPlay: count(oppStarters, "pre"), starters: oppStarters },
  };
}
