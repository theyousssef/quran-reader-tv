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

adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$APK"

# compile ahead-of-time so the reader opens and scrolls smoothly on the TV's CPU
adb -s "$TV" shell cmd package compile -m speed -f com.prayerhome.quran >/dev/null

adb -s "$TV" shell am start -n com.prayerhome.quran/.MainActivity >/dev/null
echo "Installed and opened Quran Reader."
