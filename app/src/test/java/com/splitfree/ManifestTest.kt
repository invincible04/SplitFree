package com.splitfree

import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the merged manifest to the no-service design: SplitFree declares no service of its own and
 * requests no typed `FOREGROUND_SERVICE_*` permission. Library services and the plain
 * `FOREGROUND_SERVICE` permission that WorkManager merges in are allowed to stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ManifestTest {
    private val context = RuntimeEnvironment.getApplication()

    private val packageInfo =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )

    @Test
    fun `the app declares no service of its own`() {
        val own = packageInfo.services.orEmpty().map { it.name }.filter { it.startsWith("com.splitfree.") }
        assertEquals(emptyList<String>(), own)
    }

    @Test
    fun `no typed foreground service permission is requested`() {
        val requested = packageInfo.requestedPermissions.orEmpty().toList()
        assertEquals(emptyList<String>(), requested.filter { it.startsWith("android.permission.FOREGROUND_SERVICE_") })
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
    }
}
