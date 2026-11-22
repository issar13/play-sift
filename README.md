# Play Sift

Play Sift is an Android app for people who keep a big music library on their phone. It reads the play counts from your music player and shows you what you actually listen to, what you never play and what takes up the most space. Then it helps you clear out what you don't need, safely, with nothing deleted unless you tick it and confirm.

## What it does

### See your library at a glance

The **Home** tab is the start screen. It shows:

- how many songs you have and how much space they take
- a bar showing played vs never played
- cards for **Played**, **Never played**, **Delete candidates** and **Large songs (10 MB+)**; tap a card to open its list
- highlights: your most played song, your top artist and how far back your play history goes

### Lists

- **Most played**: songs ranked by play count. When 6 or more songs share the same count, they sit under one header such as "12 plays · 7 songs", collapsed until you tap it.
- **Never played**: songs with zero plays, oldest first.
- **Delete candidates**: songs that have never been played and have been in your library longer than a cutoff you choose (6 months, 1 year or 2 years).
- **Large songs**: songs of 10 MB or more, with size and play count on each row ("14.2 MB · 12 plays"), fewest plays first, so big files you never listen to lead the list.

### Keep your play history

Music players lose their play counts when their library is rebuilt (new phone, reinstall, cleared data). Play Sift keeps its own copy of every song's plays, notices when the player resets and carries the old plays forward. Every list shows the merged total.

- Play Sift tries to save the history to `Documents/PlaySift/play-history.json` after every refresh, and once a day in the background; a failed write is not reported, so use **Export history now** in Settings when you need a confirmed backup. The new file is written completely before it replaces the old one, so a failed write keeps the previous backup. Copy the file to a new phone to keep your history.
- **Settings** has **Export history now** and **Import history**. On a fresh install with no history, Home offers to import a saved file once.
- If the old backup file belongs to an earlier install and cannot be overwritten, Play Sift writes a new one (for example `play-history (1).json`) and keeps using it.
- Songs cut from one file with a cue sheet keep separate histories.

### Send songs to Spotify, then free the space

Open **Send songs to Spotify** from Home or Settings. Play Sift makes a private Spotify playlist from:

- your **top played** songs (the top 100 by default, using total plays),
- **everything you have played**, or
- **songs you tick** in any list (use **Send to Spotify** under the list).

Songs are matched on Spotify by title, artist and length (within 5 seconds). Close matches are shown to you to confirm or skip, and songs that are not on Spotify are counted but left alone. Matches are remembered, so a second run is fast. Running the same source again updates its playlist instead of making a new one.

Sending runs in the background as a foreground service with a progress notification and a Cancel button, so you can close the app or turn the screen off (Android 13 and later asks to allow notifications the first time). Songs that were only a close match are saved and listed on the result screen for you to add or skip, and the result is still there after the app is closed. Moving songs to the trash is never done in the background.

When the playlist is ready you can open it in the Spotify app. Then, as a separate optional step, **Move N songs now on Spotify to trash** lists only the songs that were matched and added, all unticked, with **Select all**. It uses the same confirmation and Android trash as everywhere else, so nothing is deleted automatically and songs that are not on Spotify are never offered.

This needs your own free Spotify developer app (Client ID only, no password stored) with the redirect address `playsift://spotify-callback`. Put the Client ID in `local.properties` as `spotify.clientId=...` before building, or type it in the Spotify screen.

### Clean up safely

- Nothing is ticked by default, so you choose exactly what to remove.
- A confirmation shows the number of songs and their total size before anything happens, followed by Android's own prompt.
- Songs are moved to Android's **trash**, not erased, so you can restore them for about 30 days.
- A file is only removable when Play Sift can match it to exactly one file on internal storage and no other song in your library uses it. Everything else (cue-sheet tracks, streams, files on other storage, missing or shared files) is listed as "Can't remove from here" with a reason.
- A warning appears when your play history looks too short to trust.

## Requirements

- Android 11 or newer.
- A music player that exposes its library through a content provider API, with access granted to Play Sift in that player.
- Music stored on internal storage.
- Permission to read audio files, used to show file sizes. Without it, sizes are hidden and everything else still works.

## Privacy

Play Sift keeps its data on your phone: it needs no account and only reads your player's data; it never changes it. One thing to know: the play history database is part of Android's app backup, so if device backup is turned on, Android can upload that history to your Google account (Google Drive) and copy it when you move to a new phone. Turn off device backup in Android's settings if you do not want that. The only network use is the optional Spotify feature, which talks to Spotify alone and only after you sign in. Your Spotify sign-in tokens are stored encrypted with a key that never leaves your phone's Keystore.

## Build

You need JDK 21 and the Android SDK 36 (set `sdk.dir` in `local.properties`). Gradle's build cache and configuration cache are on.

Run the checks:

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Build the app you install on your phone (minified, fast):

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleRelease
```

The APK ends up in `app/build/outputs/apk/release/`. It is signed with your local debug key, which is fine for installing on your own phone but not for a store.

## Project layout

- `player/`: reads the music player's library and checks access.
- `files/`: finds file sizes and matches songs to files on the device, then moves them to the trash.
- `history/`: keeps the play history in a local database, detects player resets and writes the backup file.
- `spotify/`: Spotify sign-in (PKCE), the web API client, song matching and playlist making.
- `settings/`: stores your choices, such as the cutoff.
- `ui/`: the Jetpack Compose screens (Home, lists, Large songs, Settings, Spotify), the theme and the view model.

## Status

A personal project, growing fast. Play Sift is not affiliated with or endorsed by any music player developer. Before a big clean-up, trash one song and restore it from Android's trash to make sure it works as you expect.
