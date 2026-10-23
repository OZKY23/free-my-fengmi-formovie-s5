---
name: Free My TV
description: Install, build, adapt or customize Free My TV (github.com/simbasjungles/free-my-tv) — an open-source ad-free home screen for Google TV / Android TV, LG webOS and Samsung Tizen TVs. Use when the user mentions Free My TV, freemytv, replacing a TV's stock launcher/home screen, debloating a TV brand, or installing an APK/.ipk/.wgt on a TV over adb/SSH/SDB.
---

# Free My TV

The base directory of this skill **is the full Free My TV repository** (cloned from
github.com/simbasjungles/free-my-tv). All relative paths below are relative to this skill's base directory.

## First steps, always

1. Read **CLAUDE.md** in full and follow it: it is the authoritative guide for AI assistants (AGENTS.md just points
   there). Read before changing anything.
2. The user may not be technical: explain each step simply, say what you are about to do on their TV or computer
   **before** doing it, and ask before anything that changes their TV or computer. Let them do the taps on the TV.
3. If they didn't say, ask for: **the TV's IP address** and **their city**.
4. Never commit `config.properties`, `*.keystore` or any key. The TMDB key and Watchmode key are theirs and secret:
   they paste them into `config.properties` themselves; never ask for them in chat, never print them.

## Workflows (details in CLAUDE.md)

### Google TV / Android TV (adb)

1. Check tools: `java -version`, `$ANDROID_HOME`, `adb version`; install only what's missing (JDK 17+, Android
   command-line tools with build-tools 34 and platform android-34, adb).
2. `./build.sh` once → creates `config.properties` → fill `latitude`, `longitude`, `city`, `tmdb_region`,
   `show_crypto`, `currency`, `temperature` → run `./build.sh` again → `Built FreeMyTV.apk`.
3. `adb connect <IP>:5555` (TV asks *Allow USB debugging?* — user accepts, ticks *Always allow*), then
   `adb install -r FreeMyTV.apk`. TCL only: also the `appops set io.freemytv AUTO_START allow` line from CLAUDE.md.
4. The **user switches on the TV**: Free My TV → Home Screen → Free My TV (the app uses the TV's own ADB on
   127.0.0.1, so one more allow prompt appears). Never switch modes from the computer.
5. Check with `adb logcat -s FreeMyTV`.

### LG TVs (webOS, Python 3 + OpenSSH)

User enables Developer Mode on the TV (LG Content Store → Developer Mode → Dev Mode Status → Key Server).
Build: `python3 webos/build.py` → `Built FreeMyTV-webos.ipk`. Install: user runs `python3 webos/install.py <IP>`
themselves the first time (passphrase prompt, never in chat); later updates run from your terminal. Developer Mode
lasts ~40 days (EXTEND to renew).

### Samsung TVs (Tizen, 2017+)

Two ways: TizenBrew Installer with `simbasjungles/free-my-tv` (no tools, no TMDB key, city from time zone), or a
signed local build: `python3 tizen/build.py --sign <profile>` then `python3 tizen/install.py <IP>`. Signing needs
Samsung's `tizen` CLI and a certificate made with their Samsung account (2023+/Tizen 7+) or a Tizen certificate
(2017–2022). Certificate passwords are theirs, typed in their own terminal, never in the chat. See CLAUDE.md §
"Installing it on a Samsung TV (Tizen)".

### Adapting to another TV brand

Read-only first (getprop, `query-activities` for HOME, screensaver settings, `pm list packages -s`), then edit:

- `app/src/io/freemytv/HomeMode.java` → `DEBLOAT` (brand promo/demo apps) and `PROTECTED` (system pieces; when in
  doubt, protect).
- `app/src/io/freemytv/HomeApps.java` → `SHELF`, `LIVE_TV_APPS`, `HIDDEN`.
  Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then confirm *Original* restores
  everything. TVs without Google TV/Android TV: use a Google TV streamer on HDMI instead.

### Customizing / building

- Plain Android app, no Gradle: `./build.sh` must end with `Built FreeMyTV.apk` after any change.
- LG/Samsung share the web app in `web/` (plain ES5, no variables/grid/backdrop-filter — Chromium 2015–2017).
  Preview with `python3 webos/build.py --preview` → open `out/webos/index.html`.
- Text: `Ui.tr("English")` + catalogues in `app/assets/i18n/<lang>.json`; after changing text run
  `python3 tools/translate.py` (and `--check` to validate). Portuguese, Spanish, German, Russian, Thai are hand
  translations: edit by hand, never machine-replace.
- Settings: `config.properties` (generated into `out/gen/io/freemytv/Config.java` — never edit that file); what the
  user changes on the TV in *Home Settings* wins.
- Before pushing: `tools/check.sh` runs the Google TV build, `translate.py --check`, LG/Samsung packages and ES5
  checks.

## Safety rules (never break these)

- Never uninstall or clear data of system apps; only `pm disable-user --user 0` via `HomeMode`, so *Original* can
  restore them.
- Never turn off an app that saved a Wi-Fi network (`wifiOwners` in `HomeMode`).
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: *Original* mode and the watchdog. The stock home screen, screensaver and promo apps are
  disabled, never removed.
- If uninstalled while in Free My TV mode, restore with `adb shell pm enable <package>` plus the
  `set-home-activity` / `screensaver_components` commands listed in CLAUDE.md.
