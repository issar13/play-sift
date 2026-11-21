package app.playsift.player

import android.net.Uri

/** These values come from the player's published API. */
object PlayerContract {
    const val PLAYER_PACKAGE_NAME = "com.maxmpz.audioplayer"

    /** Oldest player build that has `total_played_times` (TableDefs.Files.TOTAL_PLAYED_TIMES, since 989). */
    const val MINIMUM_BUILD = 989L

    private const val AUTHORITY = "com.maxmpz.audioplayer.data"

    /** The authority plus the `files` table path. */
    val FILES_URI: Uri = Uri.parse("content://$AUTHORITY/files")

    /** The authority plus the `folders` table path. */
    val FOLDERS_URI: Uri = Uri.parse("content://$AUTHORITY/folders")

    /** The API receiver component class. */
    const val API_RECEIVER_CLASS = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"

    /** The API action that asks the user to grant data access. */
    const val ACTION_ASK_FOR_DATA_PERMISSION = "com.maxmpz.audioplayer.ACTION_ASK_FOR_DATA_PERMISSION"

    /** The API extra that names the calling package (the API source value is "pak"). */
    const val EXTRA_PACKAGE = "pak"

    /** The key the API documentation gives for the same extra; both are sent. */
    const val EXTRA_PACKAGE_DOCUMENTED = "package"

    /** The API action that rescans music folders. */
    const val ACTION_SCAN_DIRS = "com.maxmpz.audioplayer.ACTION_SCAN_DIRS"

    /** The API action that opens the player. */
    const val ACTION_OPEN_MAIN = "com.maxmpz.audioplayer.ACTION_OPEN_MAIN"

    /** Column names for the `files` (folder_files) and `folders` tables, from TableDefs.kt. */
    object Columns {
        /** TableDefs.Files._ID */
        const val ID = "folder_files._id"

        /** TableDefs.Files.TITLE_TAG */
        const val TITLE = "title_tag"

        /** TableDefs.Artists.ARTIST, the joined display column of the artists table. */
        const val ARTIST = "artist"

        /** TableDefs.Albums.ALBUM, the joined display column of the albums table. */
        const val ALBUM = "album"

        /** TableDefs.Files.NAME */
        const val NAME = "folder_files.name"

        /** TableDefs.Files.FOLDER_ID */
        const val FOLDER_ID = "folder_id"

        /** TableDefs.Files.FILE_PATH */
        const val FILE_PATH = "folder_files.file_path"

        /** TableDefs.Files.CUE_FOLDER_ID */
        const val CUE_FOLDER_ID = "cue_folder_id"

        /** TableDefs.Files.USER_ADDED */
        const val USER_ADDED = "folder_files.user_added"

        /** TableDefs.Files.TOTAL_PLAYED_TIMES */
        const val TOTAL_PLAYED_TIMES = "folder_files.total_played_times"

        /** TableDefs.Files.PLAYED_AT, epoch milliseconds. */
        const val PLAYED_AT = "folder_files.played_at"

        /** TableDefs.Files.CREATED_AT, epoch seconds. */
        const val CREATED_AT = "folder_files.created_at"

        /** TableDefs.Files.DURATION, milliseconds. */
        const val DURATION = "folder_files.duration"

        /** TableDefs.Files.OFFSET_MS, above zero for a virtual cue track. */
        const val OFFSET_MS = "folder_files.offset_ms"

        /** TableDefs.Folders._ID */
        const val FOLDERS_ID = "folders._id"

        /** TableDefs.Folders.IS_CUE, 1 for the virtual folder that holds cue tracks. */
        const val IS_CUE = "folders.is_cue"

        /** TableDefs.Folders.PATH, full folder path with a trailing slash. */
        const val FOLDER_PATH = "path"
    }
}
