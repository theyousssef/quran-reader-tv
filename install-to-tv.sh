#!/usr/bin/env bash
# Build Quran Reader and install it on the TV.
# Usage: ./install-to-tv.sh <tv-ip:port>                    (Settings → System → Developer options → Wireless debugging)
#        ./install-to-tv.sh <tv-ip:port> path/to/quran-reader.apk   (install a downloaded APK instead of building)
set -e
TV=${1:?usage: ./install-to-tv.sh <tv-ip:port> [apk]}
APK=${2:+$(cd "$(dirname "$2")" && pwd)/$(basename "$2")}
cd "$(dirname "$0")"

if [[ -z $APK ]]; then
  ./gradlew --console=plain -q assembleRelease
  APK=app/build/outputs/apk/release/app-release.apk
fi

SERVICE=com.prayerhome.quran/com.prayerhome.quran.audio.HomeKeyService

adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$APK"

# turn on the accessibility service that catches Home while reciting, keeping any others that are on
current=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ $current != *"$SERVICE"* && $current != *"com.prayerhome.quran/.audio.HomeKeyService"* ]]; then
  if [[ -z $current || $current == null ]]; then new=$SERVICE; else new="$current:$SERVICE"; fi
  adb -s "$TV" shell settings put secure enabled_accessibility_services "$new"
fi
adb -s "$TV" shell settings put secure accessibility_enabled 1

# compile ahead-of-time so the reader opens and scrolls smoothly on the TV's CPU
adb -s "$TV" shell cmd package compile -m speed -f com.prayerhome.quran >/dev/null

adb -s "$TV" shell am start -n com.prayerhome.quran/.MainActivity >/dev/null
echo "Installed and opened Quran Reader."
