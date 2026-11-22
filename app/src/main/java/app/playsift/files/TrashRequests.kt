package app.playsift.files

import android.app.PendingIntent
import android.content.ContentResolver
import android.provider.MediaStore

const val TRASH_BATCH_SIZE = 500

fun trashBatches(files: List<TrackFile.Removable>): List<List<TrackFile.Removable>> = files.chunked(TRASH_BATCH_SIZE)

fun trashRequest(contentResolver: ContentResolver, batch: List<TrackFile.Removable>): PendingIntent =
    MediaStore.createTrashRequest(contentResolver, batch.map { it.uri }, true)
