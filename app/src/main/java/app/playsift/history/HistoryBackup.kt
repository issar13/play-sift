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

/** Throws [IllegalArgumentException] for a file written by a newer version or with a negative or overflowing play count, and a JSON exception for a damaged file. */
fun decodeHistory(json: String): List<PlayHistoryEntry> {
    val backup = JSONObject(json)
    require(backup.getInt("version") == BACKUP_VERSION) { "Unsupported history file version ${backup.getInt("version")}" }
    val entries = backup.getJSONArray("entries")
    return (0 until entries.length()).map { index -> entryFromJson(entries.getJSONObject(index)) }
}

/**
 * Combines a backup file with the stored rows by location. Each row keeps the side with the larger total plays
 * (both counts from that one side), the earliest first-seen date and the latest last-played date.
 * Merging on totals makes importing the same file again change nothing, even after the player reset in between.
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
    val larger = if (incoming.totalPlays > current.totalPlays) incoming else current
    return current.copy(
        title = newer.title,
        artist = newer.artist,
        album = newer.album,
        lastSeenPlayerCount = larger.lastSeenPlayerCount,
        carriedPlays = larger.carriedPlays,
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

private fun entryFromJson(json: JSONObject): PlayHistoryEntry {
    val lastSeenPlayerCount = json.getInt("lastSeenPlayerCount")
    val carriedPlays = json.getInt("carriedPlays")
    require(lastSeenPlayerCount >= 0 && carriedPlays >= 0) { "A play count in the history file is negative" }
    require(lastSeenPlayerCount.toLong() + carriedPlays <= Int.MAX_VALUE) { "A play count in the history file is too large" }
    return playHistoryEntryFrom(json, lastSeenPlayerCount, carriedPlays)
}

private fun playHistoryEntryFrom(json: JSONObject, lastSeenPlayerCount: Int, carriedPlays: Int) = PlayHistoryEntry(
    folderPath = json.getString("folderPath"),
    fileName = json.getString("fileName"),
    cueTitle = json.optString("cueTitle", ""),
    title = json.getString("title"),
    artist = json.getString("artist"),
    album = json.getString("album"),
    lastSeenPlayerCount = lastSeenPlayerCount,
    carriedPlays = carriedPlays,
    firstSeenAt = Instant.parse(json.getString("firstSeenAt")),
    lastPlayedAt = if (json.isNull("lastPlayedAt")) null else Instant.parse(json.getString("lastPlayedAt")),
    updatedAt = Instant.parse(json.getString("updatedAt")),
)
