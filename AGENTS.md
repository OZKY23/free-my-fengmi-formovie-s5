# Free My TV — Fengmi/Formovie S5 Edition: guide for AI assistants

This fork is optimized for the **Fengmi (Formovie S5 "Peppa") projector** running Google TV / Android TV.

Read [CLAUDE.md](./CLAUDE.md) for the full guide. This file defines the **device-specific perimeter**.

## Device perimeter

| Area | Rule |
|---|---|
| **Device** | Fengmi/Formovie S5 only (and similar FengOS projectors). No LG webOS, Samsung Tizen, TCL, Sony or other brands. |
| **System** | Android TV / Google TV only. No webOS, Tizen. |
| **Home screen** | **Pseudo-home mode**: the projector's original home stays in place; Free My TV opens on HOME press. Does not permanently replace the home screen. |
| **Remote keys** | Only **HOME** and **Live TV** are handled by Free My TV. Other keys (Netflix, Prime, YouTube) are controlled by the projector's firmware. |
| **Projector buttons** | **Autofocus**, **Manual Keystone**, **Auto Keystone** — opened via native components `com.fengmi.projector.imaging`. |
| **Screen Mirroring** | **Mirroring** button → `cn.formovie.fengos.miracast.WfdActivity`. |
| **Voice search** | Microphone button → `VOICE_SEARCH` / `RECOGNIZE_SPEECH` native Android TV. |
| **Web remote** | HTTP server on port **8090** with PIN. Configurable from Settings → Remote Web. |
| **Content row** | Kodi favourites (JSON-RPC localhost:8080) + SmartTube subscriptions (local data file). |
| **Wallpapers** | Random from Lorem Picsum (picsum.photos, keyless). |
| **Debloat** | Only FengOS promo/device apps hidden, never uninstalled. |
| **Build** | Android (Google TV) only. No webOS/Tizen builds. |

## DOs and DON'Ts

**Always:**
- Read CLAUDE.md before any change
- Explain what you are about to do before executing
- Ask confirmation before changing projector settings
- Let the user press the remote/projector buttons themselves

**Never:**
- Ask for the Remote Web PIN in chat (read it from prefs instead)
- Change picture, audio, network or account settings on the projector
- Uninstall system apps
- Commit `config.properties` or secret keys