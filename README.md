# Play Sift

Play Sift is an Android app for people who keep a big music library on their phone. It reads the play counts from your music player and shows you what you actually listen to, what you never play and what takes up the most space. Then it helps you clear out what you don't need, safely, with nothing deleted unless you tick it and confirm.

## What it does

### See your library at a glance

The **Home** tab is the start screen. It shows:

- how many songs you have and how much space they take
- a bar showing played vs never played
- cards for **Played**, **Never played**, **Delete candidates** and **Large songs (5 MB+)**; tap a card to open its list
- highlights: your most played song, your top artist and how far back your play history goes

### Lists

- **Most played**: songs ranked by play count. When 6 or more songs share the same count, they sit under one header such as "12 plays · 7 songs", collapsed until you tap it.
- **Never played**: songs with zero plays, oldest first.
- **Delete candidates**: songs that have never been played and have been in your library longer than a cutoff you choose (6 months, 1 year or 2 years).
- **Large songs**: songs of 5 MB or more, with size and play count on each row ("8.4 MB · 12 plays"), fewest plays first, so big files you never listen to lead the list.

### Clean up safely

- Nothing is ticked by default, so you choose exactly what to remove.
- A confirmation shows the number of songs and their total size before anything happens, followed by Android's own prompt.
- Songs are moved to Android's **trash**, not erased, so you can restore them for about 30 days.
- A file is only removable when Play Sift can match it to exactly one file on internal storage and no other song in your library uses it. Everything else (cue-sheet tracks, streams, files on other storage, missing or shared files) is listed as "Can't remove from here" with a reason.
- A warning appears when your play history looks too short to trust.

## Coming next

- **Keep your play history.** Music players lose their play counts when their library is rebuilt (new phone, reinstall, cleared data). Play Sift will keep its own copy of every song's plays, notice when the player resets and carry the old plays forward, and save a backup file in `Documents/PlaySift/` that you can move to a new phone.
- **Send to Spotify, then free the space.** Make Spotify playlists from your top played songs, everything you've played, songs you pick, or your player's existing playlists. Afterwards Play Sift can offer to move the songs that are now on Spotify to the trash, using the same safe flow as above. This needs your own free Spotify developer app (Client ID only, no password stored).

## Requirements

- Android 11 or newer.
- A music player that exposes its library through a content provider API, with access granted to Play Sift in that player.
- Music stored on internal storage.
- Permission to read audio files, used to show file sizes. Without it, sizes are hidden and everything else still works.

## Privacy

Everything stays on your phone. Play Sift needs no account and only reads your player's data; it never changes it. The only network use will be the optional Spotify feature, which talks to Spotify alone and only after you sign in.

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
- `settings/`: stores your choices, such as the cutoff.
- `ui/`: the Jetpack Compose screens (Home, lists, Large songs, Settings), the theme and the view model.

## Status

A personal project, growing fast. Play Sift is not affiliated with or endorsed by any music player developer. Before a big clean-up, trash one song and restore it from Android's trash to make sure it works as you expect.
