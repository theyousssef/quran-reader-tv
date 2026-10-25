# Quran Reader

A light Quran reader for Android / Google TV, made alongside the
[Prayer Home](https://github.com/theyousssef/prayer-home-tv) launcher but independent of it: it
works the same with any launcher. Kotlin + Jetpack Compose, laid out on a 1920×1080 canvas,
driven entirely by the TV remote.

## Features

- **The whole Quran, offline.** The Uthmani text is bundled in the APK. It flows surah after surah,
  with a header and the basmala before every surah except At-Tawbah, and verse-end ornaments carrying
  Arabic-Indic numbers.
- **Three themes**, from the user's designs: *Night Amber*, *Midnight Indigo* and *Gold Leaf*. Each
  theme pairs with a Quran face, and you can change the face separately.
- **Four Quran fonts**: Scheherazade New, Amiri Quran, Harmattan and Lateef. The text size can go
  from 36 to 84 px with A− / A+.
- **Left menu** with Surah, Juz and Saved tabs, plus *Continue reading*.
- **Recitation verse by verse** from ten reciters on everyayah.com. The next verse starts with no
  gap. The page follows the recited verse and highlights it. If you scroll away, the view jumps back
  when the next verse starts.
- **Resume.** The reader reopens at the exact scroll position you left.
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
| | OK | options: **Recite from here** · **Bookmark verse** (replaces the single bookmark) |
| | Back | remove the mark |
| Menu | ▲ / ▼, OK | choose and open |
| | ▶ | Surah → Juz → Saved → close |
| | Back | close |
| Toolbar | ◀ / ▶, OK | choose; ▼ goes to the player, Back goes to the text |
| Player bar | ◀ / ▶, OK | play/pause, Stop, previous, next, repeat verse, reciter; ▲ goes to the toolbar |
| Anywhere | media keys | play/pause, stop, next, previous |

The player bar appears only after *Recite from here*. **Stop** hides it and deletes the downloaded
audio.

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

One side effect: while it holds the Home key (reader open and reciting), **holding a volume key
changes the volume by one step only**; press repeatedly instead. Everywhere else, and whenever the
recitation is paused, holding volume works as usual. The TV does this to any accessibility service that
filters remote keys.

**Without the service** (not turned on, or installed with plain `adb install`): Back still asks.
Home can't be caught, so leaving with Home while reciting pauses. After *Keep playing* on Back, the
reader can't tell what's in front, so the recitation keeps going until you pause it (the remote's
play/pause key works from anywhere) or another app starts playing sound.
To turn it on by hand: Settings → System → Accessibility → *Quran Reader: ask before leaving*, or
rerun `install-to-tv.sh`.

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
`adb install -r quran-reader.apk` works too, but then the service is off until you turn it on. To build by hand, use JDK 21 (Gradle 8.13 fails on JDK 25):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
```

For testing over adb, these extras are available:
`am start -n com.prayerhome.quran/.MainActivity --ei theme 0..2 --ei font 0..3 --es goto 18:10`.

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
- **Text layout:** each mushaf page of a surah is one paragraph inside a lazy list. Verse-end
  ornaments take up inline placeholders and are drawn in a single pass. Lines are right-aligned
  rather than justified, because Compose doesn't report justified glyph positions, and the markers
  and highlights need those positions.

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
| Gold Leaf UI | Figtree, Cormorant Garamond, Aref Ruqaa |
