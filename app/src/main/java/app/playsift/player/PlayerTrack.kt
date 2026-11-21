package app.playsift.player

import java.time.Instant

data class PlayerTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val fileName: String,
    val folderPath: String?,
    val filePath: String?,
    val isCueTrack: Boolean,
    val isUserAdded: Boolean,
    val playCount: Int,
    val lastPlayedAt: Instant?,
    val firstSeenAt: Instant,
    val durationMillis: Long,
)
