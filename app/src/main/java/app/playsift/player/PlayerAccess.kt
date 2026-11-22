package app.playsift.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

sealed interface AccessState {
    data object NotInstalled : AccessState
    data object NeedsUpdate : AccessState
    data object NeedsPermission : AccessState
    data object Ready : AccessState
}

interface Access {
    fun state(): AccessState

    fun requestPermission()

    fun requestRescan()

    fun openPlayer()
}

class PlayerAccess(private val context: Context) : Access {
    override fun state(): AccessState {
        val versionCode = try {
            context.packageManager.getPackageInfo(PlayerContract.PLAYER_PACKAGE_NAME, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            return AccessState.NotInstalled
        }
        // Mirrors the player API helper that reads the build number: version codes above 1000 carry the build times 1000.
        val build = if (versionCode > BUILD_DIVISOR_THRESHOLD) versionCode / BUILD_DIVISOR_THRESHOLD else versionCode
        if (build < PlayerContract.MINIMUM_BUILD) return AccessState.NeedsUpdate

        val probeUri = PlayerContract.FILES_URI.buildUpon().appendQueryParameter("lim", "1").build()
        return try {
            context.contentResolver
                .query(probeUri, arrayOf(PlayerContract.Columns.ID), null, null, null)
                ?.use { AccessState.Ready }
                ?: AccessState.NeedsPermission
        } catch (_: SecurityException) {
            AccessState.NeedsPermission
        }
    }

    override fun requestPermission() {
        context.sendBroadcast(
            apiIntent(PlayerContract.ACTION_ASK_FOR_DATA_PERMISSION)
                .putExtra(PlayerContract.EXTRA_PACKAGE, context.packageName)
                .putExtra(PlayerContract.EXTRA_PACKAGE_DOCUMENTED, context.packageName),
        )
    }

    override fun requestRescan() {
        context.sendBroadcast(apiIntent(PlayerContract.ACTION_SCAN_DIRS))
    }

    override fun openPlayer() {
        context.startActivity(
            Intent(PlayerContract.ACTION_OPEN_MAIN)
                .setPackage(PlayerContract.PLAYER_PACKAGE_NAME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun apiIntent(action: String): Intent =
        Intent(action).setComponent(ComponentName(PlayerContract.PLAYER_PACKAGE_NAME, PlayerContract.API_RECEIVER_CLASS))

    private companion object {
        const val BUILD_DIVISOR_THRESHOLD = 1000L
    }
}
