# Quran Reader

A light Quran reader for Android / Google TV, made alongside the
[Prayer Home](https://github.com/theyousssef/prayer-home-tv) launcher but independent of it: it
works the same with any launcher. Kotlin + Jetpack Compose, laid out on a 1920×1080 canvas,
driven entirely by the TV remote.

## Features

- **The whole Quran, offline.** The Uthmani text is bundled in the APK. It flows surah after surah,
  with a header and the basmala before every surah except At-Tawbah, and verse-end ornaments carrying
  Arabic-Indic numbers, centred on the digits themselves. The text runs on across mushaf pages: the
  next page's verses continue on the same line, and a small badge with the page number sits right
  after the marker of each page's last verse.
- **Three themes**, from the user's designs: *Night Amber*, *Midnight Indigo* and *Gold Leaf* (a soft,
  warm parchment, comfortable in a dim room). Each
  theme pairs with a Quran face, and you can change the face separately.
- **Four Quran fonts**: Scheherazade New, Amiri Quran, Harmattan and Lateef. The text size can go
  from 36 to 84 px with A− / A+.
- **Left menu** with Surah and Juz tabs, plus *Continue from bookmark*. Opening a juz goes to its
  first verse and lights it up for a few seconds, the same way the bookmark does. Each juz is labelled
  with the first words of the verse it starts at. Closed, the menu is a narrow rail showing the
  current surah and display-only icons for the bookmark, the Surah and Juz lists, and the home screen.
- **One bookmark**, always set (Al-Fatiha 1 until you move it). *Continue from bookmark* goes to it
  and lights it up for a few seconds. *Bookmark verse* moves it; there is no "remove".
- **Recitation verse by verse** from ten reciters on everyayah.com. The next verse starts with no
  gap. The page scrolls continuously: the line being recited moves through each verse in step with
  the audio, even through verses taller than the screen, and the player's progress bar and the
  surah progress bar glide along with it.
- **Resume.** The reader reopens at the exact scroll position you left (not at the bookmark).
- **Progress bar** for the current surah, driven by the first verse visible on screen.
- **Background playback** through a media foreground service with a MediaSession. The remote's
  play/pause, stop, next and previous keys work from the home screen too. Audio focus is handled:
  the reader pauses on permanent loss, and pauses then resumes on transient loss.

### Remote control

| Where | Key | Action |
|---|---|---|
| Text (nothing marked) | ▲ / ▼ | scroll |
| | ◀ | open the menu on the Surah tab |
| | ▶ | move to the toolbar (Quran font, A−, A+, reciter, theme) |
| | OK | mark the first verse on screen |
| | Back | leave the app (while reciting, asks first: see below) |
| Verse marked | ◀ / ▶ | next / previous verse (reading right to left) |
| | ▲ / ▼ | scroll and keep the mark |
| | OK | options: **Recite from here** · **Bookmark verse** (moves the bookmark) |
| | Back | remove the mark |
| Menu | ▲ / ▼, OK | choose and open (*Continue from bookmark* is at the top) |
| | ◀ | in the Surah list: jump to *Continue from bookmark*; in the Juz list (or on the card while on Juz): back to the Surah tab, landing in its list |
| | ▶ on *Continue from bookmark* | back to the list |
| | ▶ | Surah → Juz → close |
| | Back | close |
| Toolbar | ◀ / ▶, OK | choose; ◀ on *Quran font* and ▼ go back to the text; Back goes to the text |
| Anywhere | media keys | play/pause, stop, next, previous |

#### Audio Recitation mode

*Recite from here* (or the play key) starts **Audio Recitation mode**. It lasts while a recitation
exists, playing or paused. The player bar is shown, and the top bar shows the banner
**——— In Audio Recitation mode · CLICK TO EXIT ———**. The remote then works like this:

| Where | Key | Action |
|---|---|---|
| Player bar | ◀ / ▶, OK | play/pause, previous, next, repeat verse, reciter |
| | ◀ on play/pause | open the menu on the Surah tab |
| | ▲ | go to the exit banner |
| Exit banner | OK | stop reciting, close the player and delete the downloaded audio |
| | ▼ | back to play/pause |
| | ▶ | the toolbar (Quran font, A−, A+, theme); ◀ on *Quran font* or ▼ comes back to play/pause |
| Menu | ▶ | Surah → Juz → close, back on play/pause |
| | OK | the recitation moves to the surah, juz or bookmark you open, and goes on from there |
| Anywhere in the mode | Back | go to play/pause |
| Play/pause | Back | leave, as before (asks *Keep playing / Pause* when Prayer Home isn't the Home app) |

In this mode the **reciter** is chosen with the reciter button at the right end of the player bar.
The toolbar's reciter button is hidden then, and there is no Stop button. To stop, use the exit
banner (or the remote's stop key). Outside the mode the reciter button is in the toolbar.

### Leaving while reciting

If you press **Back** or **Home** while a verse is playing (not paused), the reader asks *Keep
reciting in the background?* every time:

- **Keep playing** goes to the home screen and the recitation carries on. It pauses by itself as
  soon as anything other than the home screen comes up (another app, a TV input, Settings…).
  Coming back to the reader is fine. Pop-ups that sit on top of the screen, such as the volume bar
  or Prayer Home's prayer and match banners, don't count.
- **Pause** pauses, then goes to the home screen.
- **Back** on the question closes it and you stay in the reader.

When the recitation is paused or not started, Back and Home just leave. Leaving any other way while
it plays (for example a remote button that opens another app) pauses it.

**The accessibility service.** An app can't normally see the Home key, so the reader comes with a
small accessibility service, *Quran Reader: ask before leaving*. `install-to-tv.sh` turns it on
(next to any others that are on). It only does something while the reader is on screen and
reciting: then it holds back the Home key and asks the reader to show the question. After *Keep
playing* it watches which app is in front, until the recitation pauses or you return to the reader.
The rest of the time it doesn't touch the remote at all. It doesn't read or store what's on screen.

**Volume.** While a service holds back remote keys, the TV stops repeating a held key, so holding
volume would move it only one step. To avoid that, the service steps aside whenever the volume
changes: for 2 seconds after the last change it holds back no keys at all, so holding a volume key
keeps going as usual. Once the volume has been left alone for 2 seconds, Home asks again. Pressing
Home within those 2 seconds just leaves (and pauses, as it does without the service).

The first step of a hold is never lost, but it is the step that makes the service step aside. On a
remote that repeats a held key by itself the hold then carries on normally. On one that leaves the
repeating to the TV, the first hold moves one step only; let go and hold again within 2 seconds and
it keeps going.

**Without the service** (not turned on, installed with plain `adb install`, or switched on but not
running, which can happen on TCL TVs: see *Install*): Back still asks.
Home can't be caught, so leaving with Home while reciting pauses. After *Keep playing* on Back, the
reader can't tell what's in front, so the recitation keeps going until you pause it (the remote's
play/pause key works from anywhere) or another app starts playing sound.
To turn it on by hand: Settings → System → Accessibility → *Quran Reader: ask before leaving*, or
rerun `install-to-tv.sh`. When a recitation starts without the service, the reader shows a short
note once per session.

## Install

Download `quran-reader.apk` from the [latest release](../../releases/latest). Then, with
[adb](https://developer.android.com/tools/releases/platform-tools) connected to the TV over wireless
debugging (Settings → System → Developer options; the first time, pair with `adb pair`), run:

```sh
./install-to-tv.sh <tv-ip:port> quran-reader.apk   # install a ready APK
./install-to-tv.sh <tv-ip:port>                    # or build, install and AOT-compile
```

The script also turns on the reader's accessibility service (see *Leaving while reciting*), adding
it to `enabled_accessibility_services` without removing any other service. Plain
`adb install -r quran-reader.apk` works too, but then the service is off until you turn it on.

**TCL TVs.** TCL's Google TVs have an app-start guard (*TclAppBoot*) that stops an app from being
started in the background unless it is allowed to auto-start. Installing an update stops the reader,
and the system then has to start it again in the background to run its accessibility service. The
guard blocks that, so the service stays switched on in Settings but never runs, and Home leaves
without asking. So the script:

1. allows the reader to auto-start (the `AUTO_START` and `ASSOC_START` app ops; on other TVs these
   commands do nothing), and
2. checks after installing that the service is really running. If it is on but not running, it
   takes the reader's service out of the list of accessibility services and puts it back, which makes
   the system start it. The other services in the list (Custom Mapper, for example) are left alone.

If a recitation starts while the service isn't running, the reader says so once per session, in a
small note at the bottom: *Home won't ask before leaving*.

To do the same by hand:

```sh
adb shell cmd appops set com.prayerhome.quran AUTO_START allow
adb shell cmd appops set com.prayerhome.quran ASSOC_START allow
adb shell cmd appops get com.prayerhome.quran                    # both should now say allow

# is the service running? "Quran Reader: ask before leaving" must be under "Bound services",
# not only under "Enabled services"
adb shell dumpsys accessibility | grep -A3 "Bound services"

# if it isn't: note the list, put it back without the reader's entry, then put the full list back
adb shell settings get secure enabled_accessibility_services
#   e.g. com.prayerhome.quran/.audio.HomeKeyService:com.locflix.button/.NetflixKeyService
adb shell settings put secure enabled_accessibility_services com.locflix.button/.NetflixKeyService
adb shell settings put secure enabled_accessibility_services com.prayerhome.quran/.audio.HomeKeyService:com.locflix.button/.NetflixKeyService
```

Don't force-stop the reader (`am force-stop`, `am start -S`): that stops the service too, and on a TCL
TV it may not come back until you do the steps above.

To build by hand, use JDK 21 (Gradle 8.13 fails on JDK 25):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
```

For testing over adb, these extras are available:
`am start -n com.prayerhome.quran/.MainActivity --ei theme 0..2 --ei font 0..3 --ei size 36..84 --es goto 18:10`.
`adb shell setprop log.tag.QRFollow DEBUG` logs the recitation follow loop every frame (clock, scroll,
sub-pixel offset); it is read when a recitation starts.

## How it's built

```
data/   Quran.kt   bundled text loader, surah/juz/page lookup
        Meta.kt    generated surah names, verse counts, Meccan/Medinan, juz and page starts
        Prefs.kt   settings, scroll position, bookmark
audio/  Recitation.kt      download window + gapless MediaPlayer chain + audio focus
        PlaybackService.kt mediaPlayback foreground service + MediaSession
        LeaveGuard.kt      what leaving while reciting does (ask, keep playing, pause)
        HomeKeyService.kt  accessibility service: catches Home while reciting, watches the front app
ui/     ReaderModel.kt     reading state, remote-key logic, scrolling/following
        ReaderScreen.kt    reading column, headers, verse markers, progress rail
        Parts.kt           menu, rail, toolbar, player bar, pickers, verse options
        Theme.kt, Deco.kt, Components.kt
```

The only dependencies are Compose UI/foundation, activity-compose and kotlinx-coroutines. Playback
uses the platform `MediaPlayer` and `MediaSession`, so no Media3 or ExoPlayer is needed.

- **Gapless playback:** the verse after the current one is prepared in a second `MediaPlayer`, which
  is handed to `setNextMediaPlayer`. The platform switches to it at end-of-stream on the same audio
  output. The log line `gapless: … started as next` records each hand-over.
- **Small audio footprint:** at most 10 verse files live in `cacheDir/recite` at a time: the verse
  playing plus up to 9 ahead. Each file is deleted as soon as its verse finishes, and Stop deletes
  them all. The app may be killed mid-recitation and leave files behind; the next recitation deletes
  them before it starts.
- **Text layout:** each mushaf page of a surah is one paragraph inside a lazy list. It is laid out
  once with `TextMeasurer`, cached, and drawn directly, with no `BasicText`. A page whose first verse
  fits after the previous page's last line starts with a first-line indent the width of that line. It
  is then drawn one line higher, so the two pages share the line. Verse-end ornaments take up inline
  placeholders and are drawn in a single pass. Their numbers are centred on the glyphs' ink bounds.
  Lines are right-aligned rather than justified, because Compose doesn't report justified glyph
  positions, and the markers and highlights need those positions.
- **Following the recitation:** a per-frame loop reads the `MediaPlayer` position five times a second
  (on a background thread, since the media server can block that call for ~250 ms while it chains the
  next verse) and moves an estimate forward with the vsync frame time in between. It maps the clip's
  progress to a point in the verse's laid-out lines, and the page follows that point onto a fixed
  reading line like a critically damped spring, so the speed never jumps.
- **Sub-pixel scrolling:** the recitation moves the page only 10–30 px a second, a fraction of a pixel
  per frame. The list scrolls by whole pixels (`dispatchRawDelta`) and the remaining fraction is a
  `graphicsLayer` translation of the column, drawn as an offscreen layer while reciting. The GPU then
  filters the layer between pixels, so the text glides instead of hopping a pixel every few frames
  (a plain translation doesn't help, because glyphs snap to the pixel grid). Whenever the page stops,
  it settles forward onto a whole pixel, and the layer is drawn unfiltered and stays crisp. The surah
  rail's knob and pill move the same way. The progress bars read the same values in their draw and
  layer lambdas, so nothing recomposes per frame.

## Sources and attribution

- **Quran text:** [Tanzil Project](https://tanzil.net), Uthmani text v1.1, © 2007-2026 Tanzil Project,
  CC BY 3.0. It is bundled verbatim, licence block included, in
  `app/src/main/assets/quran-uthmani.txt`. At display time, the basmala that Tanzil prefixes to
  verse 1 is shown as a separate line instead. Check for updates at https://tanzil.net/updates/.
- **Juz and page boundaries:** Tanzil `quran-data.js` metadata (CC BY 3.0).
- **Surah names and Meccan/Medinan:** [Quran.com API v4](https://api.quran.com/api/v4/chapters).
- **Recitation audio:** [EveryAyah.com](https://everyayah.com), per-verse MP3s streamed on demand
  and never redistributed. Reciter folders: Alafasy_128kbps, Husary_128kbps,
  Minshawy_Murattal_128kbps, Minshawy_Mujawwad_192kbps, Abdul_Basit_Murattal_192kbps,
  Abdul_Basit_Mujawwad_128kbps, Abdurrahmaan_As-Sudais_192kbps, MaherAlMuaiqly128kbps,
  Ghamadi_40kbps, Yasser_Ad-Dussary_128kbps.

## Fonts

All fonts are from [Google Fonts](https://github.com/google/fonts) under the SIL Open Font License 1.1.
They are subset to the characters the app uses, and variable fonts are pinned to the weights it
uses. Each family's licence is in `licenses/fonts/`.

| Use | Fonts |
|---|---|
| Quran text | Scheherazade New, Amiri Quran, Harmattan, Lateef |
| Night Amber UI | Outfit, Reem Kufi |
| Midnight Indigo UI | Literata, El Messiri |
| Gold Leaf UI | Figtree, Lora, Aref Ruqaa |
