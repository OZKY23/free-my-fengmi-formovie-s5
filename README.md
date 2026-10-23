# Free My TV — Fengmi/Formovie S5 Edition

**Fork personalizzato di [Free My TV](https://github.com/simbasjungles/free-my-tv) per proiettore Fengmi (Formovie S5 "Peppa").**

Home screen personalizzata per Google TV / Android TV con supporto specifico per proiettori Fengmi/Formovie.

## Modifiche per Fengmi/Formovie S5

- **Pseudo-home mode**: la home originale del proiettore resta attiva; il tasto HOME apre Free My TV
- **Autofocus e Keystone**: pulsanti dedicati nella riga impostazioni che aprono le utility native del proiettore
- **Mirroring**: pulsante Screen Mirroring (WiFi Cast) nativo
- **Telecomando Web**: server HTTP su porta 8090 per controllare il proiettore dalla LAN (con PIN)
- **Riconoscimento vocale**: tasto microfono mappato su VOICE_SEARCH di Android TV
- **Sfondi casuali**: sfondo automatico da Lorem Picsum (API pubblica, senza chiave)
- **Riga contenuti**: anteprime preferiti Kodi e iscrizioni SmartTube
- **Remote Web**: interfaccia QR + PIN nelle impostazioni
- **Debloat**: nasconde app cinesi/indesiderate di fabbrica (senza disinstallarle)

## Installazione

```bash
# Prerequisiti: JDK 17+, Android SDK (build-tools 34+, platform android-34), adb
./build.sh
# Configura config.properties (latitudine, longitudine, città, ecc.)
./build.sh
adb connect <IP>:5555
adb install -r FreeMyTV.apk
```

## Crediti

Progetto originale: [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv)
