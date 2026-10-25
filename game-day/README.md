# Game Day

A video window that floats on top of whatever is playing on the Google TV: Peacock,
YouTube TV, anything. Send it a YouTube, Twitch, or direct video link from your phone.
It's a separate app from Fantasy Overlay, and the two run side by side.

## Install

1. GitHub → **Actions → Build Game Day APK** → latest run → download **game-day-apk**.
2. **TV:** `adb install app-debug.apk`, then once:
   `adb shell appops set org.plasterfamily.gameday SYSTEM_ALERT_WINDOW allow`
   Open Game Day on the TV once so it starts listening.
3. **Phone:** install the same APK. It finds the TV on the Wi-Fi by itself.
   No IP address to type.

## Use

- **Send a video:** in YouTube or Chrome, tap **Share → Game Day (TV)**.
- **Widget:** long-press the home screen → Widgets → Game Day:
  **Show/Hide · Sound · Move** (cycles corners) **· Size** (small / medium / large).
- The window starts **muted**, so you hear the show underneath. Tap Sound to switch.
- Your remote always controls the app underneath, never the window.

## What plays

YouTube (videos and live streams), Twitch channels, direct `.m3u8` / `.mp4` links
(including a camera on your network), and web pages that allow embedding.
Subscription apps (Peacock, Prime, YouTube TV) can't play inside the window.

## Control API (port 8766, local network)

`/status` · `/show?u=LINK` · `/hide` · `/toggle` · `/sound` (`?on=1|0`) ·
`/pos?p=bl|tl|tr|br|next` · `/size?s=s|m|l|next` · `/reload`
