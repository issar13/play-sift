package app.playsift.player

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
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
    @Serializable(with = InstantIsoSerializer::class) val lastPlayedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class) val firstSeenAt: Instant,
    val durationMillis: Long,
)
