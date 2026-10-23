# Fantasy Overlay

A fantasy football panel that sits on the right quarter of your TV, on top of whatever
is playing (YouTube TV, Peacock, Plex, anything on the Google TV device).

```
worker/          Cloudflare Worker: pulls your ESPN matchup, serves the panel page
android/         Android TV app: draws the panel over other apps
homeassistant/   Show / hide / toggle commands and an optional Sunday schedule
.github/         GitHub Actions workflow that builds the APK for you
```

---

## 1. Get your league details

Open your team on fantasy.espn.com. The URL looks like
`fantasy.espn.com/football/team?leagueId=1234567&teamId=3`.

- **LEAGUE_ID** = the leagueId number
- **TEAM_ID** = the teamId number

**Private league only:** you also need two cookies from espn.com. Sign in to ESPN in Chrome
on a computer, open DevTools (F12) → Application → Cookies → `https://www.espn.com`, and copy:

- `espn_s2` (a long string)
- `SWID` (looks like `{ABCD-1234-...}`, keep the braces)

## 2. Deploy the panel (Cloudflare Worker)

```bash
cd worker
# edit wrangler.toml: set LEAGUE_ID and TEAM_ID
npx wrangler deploy
npx wrangler secret put ESPN_S2      # private league only
npx wrangler secret put SWID         # private league only
```

This publishes **https://fantasy-panel.faufan.workers.dev/panel** (or your custom domain if you add one).

- Test it first in any browser: `https://fantasy.plasterfamily.org/panel?demo=1` shows sample
  data that scores every few refreshes so you can see the alerts.
- Then try `/panel` without demo to see your real matchup.
- **Cloudflare Access:** if Access covers all of `*.plasterfamily.org`, add a Bypass for
  `fantasy.plasterfamily.org`. The TV can't complete an Access sign-in. To keep the page
  private anyway, run `npx wrangler secret put ACCESS_KEY`, then use
  `/panel?key=YOUR_KEY` as the panel URL.

Panel URL options (combine with `&`):

| Option | Effect |
|---|---|
| `demo=1` | Sample data |
| `opacity=0.8` | Panel background see-through amount (default 0.88) |
| `every=15` | Refresh every N seconds (default 20, min 10) |
| `alert=4` | Only pop an alert when a player gains at least N points (default 2) |

## 3. Build the TV app (no Android Studio needed)

1. Create a new **private** GitHub repo and push this whole folder to it.
2. Open the repo's **Actions** tab. The "Build overlay APK" workflow runs on push
   (or click **Run workflow**).
3. When it finishes, download the **fantasy-overlay-apk** artifact and unzip it to get `app-debug.apk`.

If you'd rather use a different panel address, change `default_url` in
`android/app/src/main/res/values/strings.xml` before pushing. You can also change it later from the
app or from Home Assistant.

## 4. Install it on the Google TV

On the TV, go to **Settings → System → About** and click **Android TV OS build** seven times to
unlock Developer options. Then, in **Settings → System → Developer options**, turn on
**Wireless debugging**, choose **Pair device with pairing code**, and from a computer with
[platform-tools](https://developer.android.com/tools/releases/platform-tools) run:

```bash
adb pair TV_IP:PAIR_PORT            # enter the code shown on the TV
adb connect TV_IP:PORT              # the port shown on the Wireless debugging screen
adb install app-debug.apk
adb shell appops set org.plasterfamily.fantasyoverlay SYSTEM_ALERT_WINDOW allow
```

The last line grants "display over other apps." Google TV hides that setting, so it's a
one-time command. Older devices without Wireless debugging use **USB/Network debugging** and
`adb connect TV_IP:5555` instead.

## 5. Try it

1. Open **Fantasy Overlay** from the TV's apps row.
2. Press **Demo data: off** to switch it on, then press **Show overlay**.
3. Press **Home** and start any show. The panel stays on top, and the remote still controls the show.
4. Switch demo data back off when your Worker is set up.

The settings screen also shows the TV's control address, which you'll need for Home Assistant, and lets you change
width (20/25/30/33%), side (left or right), and the panel URL. The overlay comes back after a reboot if it
was showing.

## 6. Home Assistant

Paste `homeassistant/fantasy_overlay.yaml` into your configuration, replace `TV_IP`, and restart.
You get `script.fantasy_overlay_toggle` for an Echo Show or phone dashboard button, plus an
optional automation that shows the panel at 12:55 PM on Sundays and hides it at midnight.

Give the TV a fixed IP (a DHCP reservation on your router) so the address doesn't change.

## 7. Phone widget (Full / Score / Auto / Off)

Install the same APK on your Android phone, open it once, and enter the TV's address
(shown on the TV's copy of the settings screen, e.g. `192.168.1.50`) under
**Phone widget**, then **Save**. Long-press your home screen → **Widgets** → **Fantasy Overlay**.

- **Full**: the whole panel
- **Score**: just the score card; the rest of the panel disappears so the game shows through
- **Auto**: score card, opens to the full panel for 20 seconds on a big play (6+ points)
- **Off**: hidden

The buttons talk to the TV over your home Wi-Fi, so they only work at home. Tap the score
card on the widget to refresh it (Android otherwise refreshes widgets about every 30 minutes).

The APK is now signed with a fixed key (`android/app/fantasy.keystore`), so future builds
install as updates. **The first time**, uninstall the old Fantasy Overlay from the phone and
the TV before installing this one, then redo the overlay permission.

### Control API (port 8765, local network only)

| Request | Does |
|---|---|
| `/show` `/hide` `/toggle` | Show or hide the panel |
| `/reload` | Reload the panel page |
| `/mode?m=score` | Panel mode: `full`, `score`, `auto`, `off` |
| `/set?width=30` | Width as % of screen (10 to 50) |
| `/set?side=left` | Dock left or right |
| `/set?url=...` | Change the page (URL-encode it) |
| `/status` | Current state as JSON |

## Limits worth knowing

- The panel covers only what the Google TV device itself shows. If you switch the TV to another
  HDMI input (cable box, console), the panel isn't on screen.
- Android hides overlays on a few system screens (settings, permission prompts), so the panel can
  disappear there briefly.
- The overlay covers the right side of the picture rather than shrinking it. Lower the opacity or
  toggle it off when a replay or score bug is under it.
- ESPN's fantasy API is unofficial. If ESPN changes it, the Worker may need an update. The panel
  keeps showing the last good data and an error line at the bottom until then.
- "Big Play" means a player gained 6+ points between refreshes. The panel infers it from point
  changes and doesn't confirm it was a touchdown.
