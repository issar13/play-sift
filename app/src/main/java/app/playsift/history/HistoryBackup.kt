package app.playsift.history

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

private const val BACKUP_VERSION = 1

fun encodeHistory(entries: List<PlayHistoryEntry>, exportedAt: Instant): String =
    JSONObject()
        .put("version", BACKUP_VERSION)
        .put("exportedAt", exportedAt.toString())
        .put("entries", JSONArray(entries.map(::entryToJson)))
        .toString()

/** Throws [IllegalArgumentException] for a file written by a newer version and a JSON exception for a damaged file. */
fun decodeHistory(json: String): List<PlayHistoryEntry> {
    val backup = JSONObject(json)
    require(backup.getInt("version") == BACKUP_VERSION) { "Unsupported history file version ${backup.getInt("version")}" }
    val entries = backup.getJSONArray("entries")
    return (0 until entries.length()).map { index -> entryFromJson(entries.getJSONObject(index)) }
}

/**
 * Combines a backup file with the stored rows by location, taking the larger counts and the earliest first-seen date.
 * Larger counts keep the total right: the next snapshot treats a lower player count as a reset and carries the larger one.
 * Returns only the rows that the file touches.
 */
fun mergeImported(existing: List<PlayHistoryEntry>, imported: List<PlayHistoryEntry>): List<PlayHistoryEntry> {
    val existingByKey = existing.associateBy { it.historyKey() }
    return imported.map { incoming ->
        existingByKey[incoming.historyKey()]?.let { current -> mergeRows(current, incoming) } ?: incoming
    }
}

private fun mergeRows(current: PlayHistoryEntry, incoming: PlayHistoryEntry): PlayHistoryEntry {
    val newer = if (incoming.updatedAt.isAfter(current.updatedAt)) incoming else current
    return current.copy(
        title = newer.title,
        artist = newer.artist,
        album = newer.album,
        lastSeenPlayerCount = maxOf(current.lastSeenPlayerCount, incoming.lastSeenPlayerCount),
        carriedPlays = maxOf(current.carriedPlays, incoming.carriedPlays),
        firstSeenAt = minOf(current.firstSeenAt, incoming.firstSeenAt),
        lastPlayedAt = listOfNotNull(current.lastPlayedAt, incoming.lastPlayedAt).maxOrNull(),
        updatedAt = newer.updatedAt,
    )
}

private fun entryToJson(entry: PlayHistoryEntry): JSONObject =
    JSONObject()
        .put("folderPath", entry.folderPath)
        .put("fileName", entry.fileName)
        .put("cueTitle", entry.cueTitle)
        .put("title", entry.title)
        .put("artist", entry.artist)
        .put("album", entry.album)
        .put("lastSeenPlayerCount", entry.lastSeenPlayerCount)
        .put("carriedPlays", entry.carriedPlays)
        .put("firstSeenAt", entry.firstSeenAt.toString())
        .put("lastPlayedAt", entry.lastPlayedAt?.toString() ?: JSONObject.NULL)
        .put("updatedAt", entry.updatedAt.toString())

private fun entryFromJson(json: JSONObject) = PlayHistoryEntry(
    folderPath = json.getString("folderPath"),
    fileName = json.getString("fileName"),
    cueTitle = json.optString("cueTitle", ""),
    title = json.getString("title"),
    artist = json.getString("artist"),
    album = json.getString("album"),
    lastSeenPlayerCount = json.getInt("lastSeenPlayerCount"),
    carriedPlays = json.getInt("carriedPlays"),
    firstSeenAt = Instant.parse(json.getString("firstSeenAt")),
    lastPlayedAt = if (json.isNull("lastPlayedAt")) null else Instant.parse(json.getString("lastPlayedAt")),
    updatedAt = Instant.parse(json.getString("updatedAt")),
)
