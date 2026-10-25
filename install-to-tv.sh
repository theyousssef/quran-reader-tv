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
SHORT=com.prayerhome.quran/.audio.HomeKeyService
LABEL="Quran Reader: ask before leaving"

adb connect "$TV" >/dev/null

# TCL's app-start guard (TclAppBoot) won't start an app in the background unless it may auto-start.
# Without this, the system can't bring the reader's accessibility service back after an update or
# after the app was stopped: the service stays "on" in Settings but never runs, so Home just leaves.
# Set before installing, so the service can start as soon as the new APK is in. No-op on other TVs.
adb -s "$TV" shell cmd appops set com.prayerhome.quran AUTO_START allow 2>/dev/null || true
adb -s "$TV" shell cmd appops set com.prayerhome.quran ASSOC_START allow 2>/dev/null || true

adb -s "$TV" install -r "$APK"
# (the app ops are kept across updates, but a first install creates them only now)
adb -s "$TV" shell cmd appops set com.prayerhome.quran AUTO_START allow 2>/dev/null || true
adb -s "$TV" shell cmd appops set com.prayerhome.quran ASSOC_START allow 2>/dev/null || true

# turn on the accessibility service that catches Home while reciting, keeping any others that are on
current=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ $current != *"$SERVICE"* && $current != *"$SHORT"* ]]; then
  if [[ -z $current || $current == null ]]; then current=$SERVICE; else current="$current:$SERVICE"; fi
  adb -s "$TV" shell settings put secure enabled_accessibility_services "$current"
fi
adb -s "$TV" shell settings put secure accessibility_enabled 1

# Is the service actually running? dumpsys lists running ones under "Bound services" by label.
bound() {
  adb -s "$TV" shell dumpsys accessibility | tr -d '\r' |
    awk '/Bound services/{on=1} /Enabled services/{on=0} on' | grep -q "$LABEL"
}
for _ in 1 2 3; do bound && break; sleep 1; done
if ! bound; then
  # On but not running: take only ours out of the list and put the list back, which makes the
  # system bind it again. Every other service in the list (e.g. Custom Mapper) stays as it was.
  echo "The accessibility service is on but not running; restarting it."
  others=$(tr ':' '\n' <<<"$current" | grep -v -x -e "$SERVICE" -e "$SHORT" | paste -sd: -)
  if [[ -n $others ]]; then adb -s "$TV" shell settings put secure enabled_accessibility_services "$others"
  else adb -s "$TV" shell settings delete secure enabled_accessibility_services >/dev/null; fi
  sleep 1
  adb -s "$TV" shell settings put secure enabled_accessibility_services "$current"
  for _ in 1 2 3 4 5; do sleep 1; bound && break; done
fi
if bound; then echo "Accessibility service running."
else echo "Warning: the accessibility service isn't running; Home won't ask before leaving (see README)."; fi

# compile ahead-of-time so the reader opens and scrolls smoothly on the TV's CPU
adb -s "$TV" shell cmd package compile -m speed -f com.prayerhome.quran >/dev/null

adb -s "$TV" shell am start -n com.prayerhome.quran/.MainActivity >/dev/null
echo "Installed and opened Quran Reader."
