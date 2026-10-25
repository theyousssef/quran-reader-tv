# Quran Reader

A light Quran reader for Android / Google TV, made as a companion to the
[Prayer Home](https://github.com/theyousssef/prayer-home-tv) launcher. Kotlin + Jetpack Compose, laid out on a 1920×1080 canvas,
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
| | Back | leave the app |
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

### Leaving while reciting (with Prayer Home)

If you press Home or Back while a verse is playing, the reader waits up to 2.5 s for Prayer Home's
`com.prayerhome.quran.action.HOME_SHOWN`.

- **Home came up:** playback continues and the reader sends `com.prayerhome.tv.action.ASK_KEEP_PLAYING`
  (extra `title`). Prayer Home then asks *Keep playing / Pause*, and answers with `…action.PAUSE` or
  `…action.KEEP_PLAYING`.
- **Something else opened:** the reader pauses.
- **Leaving Home later:** while the reader plays in the background, Prayer Home's `…action.HOME_HIDDEN`
  pauses it, unless the reader itself is the app coming back to the front.

If Prayer Home isn't the Home app, the reader asks in-app on Back and pauses on Home.

## Install

Download `quran-reader.apk` from the [latest release](../../releases/latest). Then, with
[adb](https://developer.android.com/tools/releases/platform-tools) connected to the TV over wireless
debugging (Settings → System → Developer options; the first time, pair with `adb pair`), run:

```sh
./install-to-tv.sh <tv-ip:port> quran-reader.apk   # install a ready APK
./install-to-tv.sh <tv-ip:port>                    # or build, install and AOT-compile
```

Plain `adb install -r quran-reader.apk` works too. Nothing else is needed: no permissions to grant,
and nothing to disable. To build by hand, use JDK 21 (Gradle 8.13 fails on JDK 25):

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
        HomeBridge.kt      the Prayer Home contract (manifest receiver ControlReceiver)
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
