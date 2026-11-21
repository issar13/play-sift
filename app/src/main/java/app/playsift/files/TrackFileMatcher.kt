package app.playsift.files

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import app.playsift.player.PlayerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface TrackFile {
    val track: PlayerTrack

    data class Removable(override val track: PlayerTrack, val uri: Uri, val sizeBytes: Long) : TrackFile

    data class NotRemovable(override val track: PlayerTrack, val reason: NotRemovableReason) : TrackFile
}

enum class NotRemovableReason { CueTrack, StreamOrProvider, NotOnInternalStorage, NotFoundOnPhone, AmbiguousMatch }

private const val INTERNAL_VOLUME_PREFIX = "primary/"

/** Turns a player folder path such as `primary/Music/Artist/` into a MediaStore `RELATIVE_PATH`. */
fun mediaStoreLocation(folderPath: String): String? =
    folderPath.takeIf { it.startsWith(INTERNAL_VOLUME_PREFIX) }?.removePrefix(INTERNAL_VOLUME_PREFIX)

interface FileMatcher {
    suspend fun match(tracks: List<PlayerTrack>): List<TrackFile>
}

class TrackFileMatcher(private val contentResolver: ContentResolver) : FileMatcher {
    private val audioCollection = MediaStore.Audio.Media.getContentUri("external_primary")

    override suspend fun match(tracks: List<PlayerTrack>): List<TrackFile> =
        withContext(Dispatchers.IO) {
            val matchedFiles = tracks.map(::matchTrack)
            val sharedUris = matchedFiles
                .filterIsInstance<TrackFile.Removable>()
                .groupingBy { it.uri }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            matchedFiles.map { file ->
                if (file is TrackFile.Removable && file.uri in sharedUris) {
                    TrackFile.NotRemovable(file.track, NotRemovableReason.AmbiguousMatch)
                } else {
                    file
                }
            }
        }

    private fun matchTrack(track: PlayerTrack): TrackFile {
        val mediaStoreLocation = track.folderPath?.let(::mediaStoreLocation)
        return when {
            track.isCueTrack -> TrackFile.NotRemovable(track, NotRemovableReason.CueTrack)
            track.filePath != null || track.isUserAdded ->
                TrackFile.NotRemovable(track, NotRemovableReason.StreamOrProvider)
            mediaStoreLocation == null -> TrackFile.NotRemovable(track, NotRemovableReason.NotOnInternalStorage)
            else -> queryMediaStore(track, mediaStoreLocation)
        }
    }

    private fun queryMediaStore(track: PlayerTrack, relativePath: String): TrackFile =
        contentResolver
            .query(
                audioCollection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(relativePath, track.fileName),
                null,
            )!!
            .use { cursor ->
                when (cursor.count) {
                    0 -> TrackFile.NotRemovable(track, NotRemovableReason.NotFoundOnPhone)
                    1 -> {
                        cursor.moveToFirst()
                        TrackFile.Removable(
                            track = track,
                            uri = ContentUris.withAppendedId(audioCollection, cursor.getLong(0)),
                            sizeBytes = cursor.getLong(1),
                        )
                    }
                    else -> TrackFile.NotRemovable(track, NotRemovableReason.AmbiguousMatch)
                }
            }
}
