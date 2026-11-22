package app.playsift.player

import android.content.ComponentName
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerAccessTest {
    private val application = RuntimeEnvironment.getApplication()
    private val access = PlayerAccess(application)

    @Before
    fun setUp() {
        FakePlayerProvider.throwSecurityException = false
        FakePlayerProvider.returnNullCursor = false
        FakePlayerProvider.database = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE folder_files (_id INTEGER PRIMARY KEY)")
        }
        Robolectric.buildContentProvider(FakePlayerProvider::class.java).create("com.maxmpz.audioplayer.data")
    }

    private fun installPlayer(versionCode: Long) {
        val packageInfo = PackageInfo().apply {
            packageName = PlayerContract.PLAYER_PACKAGE_NAME
            longVersionCode = versionCode
        }
        shadowOf(application.packageManager).installPackage(packageInfo)
    }

    @Test
    fun notInstalledWhenPackageMissing() {
        assertEquals(AccessState.NotInstalled, access.state())
    }

    @Test
    fun needsUpdateBelowBuild989() {
        installPlayer(988_000L)

        assertEquals(AccessState.NeedsUpdate, access.state())
    }

    @Test
    fun needsPermissionWhenProbeThrowsSecurityException() {
        installPlayer(989_000L)
        FakePlayerProvider.throwSecurityException = true

        assertEquals(AccessState.NeedsPermission, access.state())
    }

    @Test
    fun nullProbeCursorNeedsPermission() {
        installPlayer(989_000L)
        FakePlayerProvider.returnNullCursor = true

        assertEquals(AccessState.NeedsPermission, access.state())
    }

    @Test
    fun readyWhenProbeSucceeds() {
        installPlayer(989_000L)

        assertEquals(AccessState.Ready, access.state())
    }

    @Test
    fun requestPermissionSendsBothPackageExtraKeys() {
        access.requestPermission()

        val intent = shadowOf(application).broadcastIntents.single()
        assertEquals(PlayerContract.ACTION_ASK_FOR_DATA_PERMISSION, intent.action)
        assertEquals(ComponentName(PlayerContract.PLAYER_PACKAGE_NAME, PlayerContract.API_RECEIVER_CLASS), intent.component)
        assertEquals(application.packageName, intent.getStringExtra("pak"))
        assertEquals(application.packageName, intent.getStringExtra("package"))
    }

    @Test
    fun rescanSendsNoFullRescanExtra() {
        access.requestRescan()

        val intent = shadowOf(application).broadcastIntents.single()
        assertEquals(PlayerContract.ACTION_SCAN_DIRS, intent.action)
        assertEquals(ComponentName(PlayerContract.PLAYER_PACKAGE_NAME, PlayerContract.API_RECEIVER_CLASS), intent.component)
        assertNull(intent.extras)
    }
}
