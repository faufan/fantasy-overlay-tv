# Fantasy Overlay for Google TV

Floats your fantasy panel down the right side of the TV, on top of whatever
you're watching (YouTube TV, Peacock, NFL+, anything). The remote keeps
controlling the game; the panel just sits on top and updates every 20 seconds.

It shows the `/panel` page from your Cloudflare Worker, so your Worker needs
to be deployed first with `ACCESS_KEY`, `LEAGUE_ID` and `TEAM_ID` set.

---

## 1. Turn the code into an APK (pick one)

### Option A: GitHub (no software to install)
1. Make a new **private** repository on github.com.
2. Click **uploading an existing file** and drag in everything in this folder,
   including the hidden `.github` folder. (If your computer hides it, use
   GitHub Desktop or `git push` instead.)
3. Open the **Actions** tab. The "Build APK" run starts on its own and takes
   about 3–5 minutes.
4. Open the finished run and download **FantasyOverlay-apk**. Unzip it to get
   `app-debug.apk`.

### Option B: Android Studio
Open this folder in Android Studio, let it sync, then choose
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

---

## 2. Install it on the Google TV

Easiest way, using the free **Downloader** app:

1. On the TV: **Settings → System → About**, then click **Android TV OS build**
   7 times. This turns on Developer options.
2. Install **Downloader** (by AFTVnews) from the Play Store.
3. **Settings → Apps → Security & restrictions → Unknown sources** → turn on
   **Downloader**.
4. Put `app-debug.apk` somewhere with a direct download link (Google Drive
   "anyone with the link", Dropbox, etc.), open that link in Downloader, and
   install.

Or with a computer on the same Wi-Fi (Developer options → **USB debugging** on):

```
adb connect <TV-IP-ADDRESS>
adb install app-debug.apk
```

---

## 3. Allow it to show over other apps (one time)

Open **Fantasy Overlay** and press **Allow overlay**.

Many Google TV devices hide this setting. If the app tells you so, run this
once from a computer (same Wi-Fi, USB debugging on):

```
adb connect <TV-IP-ADDRESS>
adb shell appops set com.fantasyoverlay.tv SYSTEM_ALERT_WINDOW allow
```

---

## 4. Use it

1. Open **Fantasy Overlay**, enter your Worker address
   (`https://….workers.dev`) and your `ACCESS_KEY`, then press **Save**.
   Typing tip: the Google TV app on your phone has a keyboard that types into
   the TV.
2. Press **Show overlay**, then press **Back** — you land back in your game
   with the panel on the right.
3. To hide it, open Fantasy Overlay again and press **Hide overlay**.

**Width** and **Opacity** cycle through a few sizes. **Demo data** shows fake
scores so you can check the look without a live game.

## Notes
- Your key is stored only on the TV, in the app's private storage.
- The panel only loads your Worker's address; it can't navigate anywhere else.
- A few streaming apps may hide or dim overlays on protected video. If one
  does, try another app or the laptop setup.
