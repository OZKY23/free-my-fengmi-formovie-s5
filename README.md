<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://github.com/simbasjungles/free-my-tv/raw/main/.github/preview.webp">
  <img alt="Free My TV home screen" src="https://github.com/simbasjungles/free-my-tv/raw/main/.github/preview.webp" width="800">
</picture>

# Free My TV — Fengmi/Formovie S5 Edition

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Build](https://github.com/OZKY23/free-my-tv-fengmi/actions/workflows/check.yml/badge.svg)](https://github.com/OZKY23/free-my-tv-fengmi/actions/workflows/check.yml)

> **Fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) optimized for the Fengmi (Formovie S5 "Peppa") projector** running Google TV / Android TV with FengOS.

Replaces the stock launcher with a clean, ad-free home screen while adding projector-specific controls: **autofocus, keystone (manual & auto), screen mirroring, web remote**, and a content row showing **Kodi favourites and SmartTube subscriptions**.

---

## ✨ Features

### Projector-specific
| Feature | Detail |
|---|---|
| **Pseudo-home mode** | Original home screen stays; HOME key opens Free My TV |
| **Autofocus** | Opens native focus utility (`com.fengmi.projector.imaging.FocusActivity`) |
| **Keystone (manual)** | Opens native keystone adjustment (`KeystoneActivity`) |
| **Keystone (auto)** | Opens native auto-keystone (`AutoKeystoneActivity`) |
| **Screen Mirroring** | Launches native Miracast (`cn.formovie.fengos.miracast.WfdActivity`) |
| **Voice search** | Microphone key → Android TV VOICE_SEARCH / RECOGNIZE_SPEECH |

### Home screen
| Feature | Detail |
|---|---|
| **Minimal layout** | Centred clock, input row (TV / HDMI / Mirroring), app shelf, settings row, all-apps grid |
| **Random wallpapers** | New photo every 15 min from Lorem Picsum (free, no API key) |
| **Content row** | Kodi favourites & recently added + SmartTube subscriptions (fetched every 5 min) |
| **Background shading** | Dark gradient from top & bottom, deeper at night |
| **Auto night mode** | Dark theme from sunset to sunrise (based on your coordinates) |
| **Weather & crypto** | Optional weather widget + Bitcoin/Ethereum/Solana prices (Open-Meteo, Binance) |
| **Debloat** | Hides FengOS promo apps (never uninstalled — restore with *Original* mode) |

### Web remote
| Feature | Detail |
|---|---|
| **LAN remote control** | HTTP server on port 8090 with 4-digit PIN auth |
| **Full D-pad** | Navigate and control the projector from any device on the same network |
| **App launcher** | Launch any installed app remotely |
| **QR code** | Auto-generated QR + URL in Settings → Remote Web |

---

## 🔧 Installation

### Prerequisites

- JDK 17+
- Android SDK command-line tools (build-tools 34+, platform android-34)
- ADB (Android Debug Bridge)

### Build

```bash
./build.sh                           # first run: creates config.properties
# edit config.properties with your city, lat/lon, TMDB key, etc.
./build.sh                           # second run: builds FreeMyTV.apk
```

Keep `freemytv.keystore` — updates must be signed with the same key.

### Install

```bash
adb connect <projector-IP>:5555      # accept "Allow USB debugging?" on the projector
adb install -r FreeMyTV.apk
```

### First launch

1. On the projector: **Free My TV → Home Screen → Free My TV**
2. The projector asks once more to allow debugging (accept, tick *Always allow*)
3. The home screen appears. Press **HOME** on the remote to switch between Free My TV and the original launcher.

---

## ⚙️ Configuration

Edit `config.properties` in the project root (never commit it):

| Setting | Description |
|---|---|
| `tmdb_key` | Free TMDB API key (themoviedb.org) — enables trending picks |
| `tmdb_region` | Your country code (IT, US, GB, BR…) |
| `latitude` / `longitude` | Your coordinates — for weather & auto night mode |
| `city` | City name shown on the weather card |
| `language` | UI language (en, it, pt, es, de, fr, ja, zh-CN…) |
| `currency` | Crypto prices currency (EUR, USD, BRL…) |
| `temperature` | C, F or auto |
| `show_crypto` | true/false — Bitcoin, Ethereum, Solana prices |
| `disable_assistant` | true — also turns off Google Assistant (mic button stops working) |

---

## 🎮 Remote Web

Enable in **Settings → Remote Web**:

1. Press OK on *Remote Web* to toggle it on (generates a PIN automatically)
2. Scan the QR code or open the displayed URL from any device on the same LAN
3. Enter the 4-digit PIN — you now have full remote control of the projector from your phone/tablet

> The PIN is shown on the Remote Web settings screen. Never share it publicly.

---

## 🎯 Content Row

The row between the clock and the shelf shows **Kodi favourites** and **SmartTube subscriptions**:

- **Kodi**: queried via JSON-RPC at `http://localhost:8080/jsonrpc` (default Kodi HTTP API port)
- **SmartTube**: reads `subscriptions.json` from the SmartTube data directory
- Auto-refreshes every 5 minutes. Navigate with ← → and press OK to open the app.

---

## 🔨 Building from source

```bash
./build.sh              # builds FreeMyTV.apk
```

For individual components:
- `tools/translate.py` — update translations after adding UI strings
- `tools/make_symbols.py` — regenerate symbol PNGs from SVGs
- `tools/check.sh` — full pre-push check (build + translations + web app)

---

## 📦 Project structure

```
app/                     # Android app (Java source, resources, i18n, fonts)
  src/io/freemytv/       # all Java source files
  assets/i18n/           # translations (26 languages)
  assets/symbols/        # UI icons (SVG → PNG)
  res/                   # Android resources (strings, bools, styles)
build.sh                 # APK builder
install.sh               # ADB installer
config.example.properties # template for config (copy to config.properties)
CONTRIBUTING.md          # (TODO)
web/                     # Web app (shared LG/Samsung frontend)
webos/                   # LG webOS packaging (not used on Fengmi)
tizen/                   # Samsung Tizen packaging (not used on Fengmi)
docs/                    # Website (freemytv.io, GitHub Pages)
tools/                   # Build & translation utilities
```

---

## 📜 License

[GPL-3.0](LICENSE). Based on the original [Free My TV](https://github.com/simbasjungles/free-my-tv) by simbasjungles.