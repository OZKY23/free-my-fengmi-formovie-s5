#!/bin/sh
# Installs FreeMyTV.apk on the TV and fixes the two things an adb update breaks on Fengmi/FengOS:
# 1) the update disables the accessibility service (the remote-keys filter) -> enable it again;
# 2) the update puts the app in the stopped state -> BOOT_COMPLETED is discarded and the home
#    doesn't open by itself after a reboot -> start it once (RemoteKeyService now also covers that,
#    but starting it here makes the stopped state go away right away).
# Usage: ./install.sh [adb serial]     (serial default: 192.168.1.143:5555)
set -e
SERIAL=${1:-192.168.1.143:5555}
adb connect "$SERIAL" >/dev/null 2>&1 || true
adb -s "$SERIAL" install -r FreeMyTV.apk
# A change in the setting is what makes the system rebind the service: empty first, then ours.
adb -s "$SERIAL" shell "settings put secure enabled_accessibility_services ''; sleep 1; settings put secure enabled_accessibility_services io.freemytv/io.freemytv.RemoteKeyService; settings put secure accessibility_enabled 1"
n=0
while [ $n -lt 3 ]; do
    n=$((n + 1))
    if adb -s "$SERIAL" shell "am start -n io.freemytv/.HomeActivity" 2>&1 | grep -q "Starting"; then
        echo "app avviata (tentativo $n)"
        break
    fi
    sleep 3
done
echo "Installato e servizio tasti riattivato su $SERIAL"
