# Play Sift

Play Sift is an Android app that reads the play counts from your music player and shows three lists: your most played songs, the songs you have never played, and delete candidates, which are songs that have never been played and have been in your library longer than a cutoff you choose. It helps you clear out music you added but never listened to.

## Features

- Three lists: most played, never played, and delete candidates.
- Adjustable cutoff for delete candidates: 6 months, 1 year or 2 years.
- Nothing is ticked by default, so you choose exactly what to remove.
- A confirmation shows the number of songs and their total size before anything happens.
- Songs are moved to Android's trash, so files can be restored for about 30 days.
- Files that Play Sift can't safely remove are listed with a reason.
- A warning appears when your play history looks too short to trust.

## Requirements

- Android 11 or newer.
- A music player that exposes its library through a content provider API, with access granted to Play Sift in that player.
- Music stored on internal storage.

## Privacy

Everything stays on your phone. Play Sift uses no network, needs no account, and only reads your player's data; it never changes it.

## Build

You need JDK 21 and the Android SDK 36 (set `sdk.dir` in `local.properties`).

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`.

## Project layout

- `player/`: reads the music player's library and checks access.
- `files/`: matches tracks to files on the device and moves them to the trash.
- `settings/`: stores your choices, such as the cutoff.
- `ui/`: the Jetpack Compose screens and view model.

## Status

Early days. Play Sift is not affiliated with or endorsed by any music player developer. Before a big clean-up, try trashing one song and restoring it to make sure it works as you expect.
