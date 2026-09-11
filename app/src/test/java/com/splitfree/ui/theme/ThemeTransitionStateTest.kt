package com.splitfree.ui.theme

import android.app.Application
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ThemeTransitionStateTest {
    @Before
    fun resetState() {
        ThemeTransitionState.clear()
        ThemeTransitionState.animationDone = true
    }

    private fun laidOutView(): View = View(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 10, 10) }

    @Test
    fun `captureAndChange stores the overlay and defers the change`() {
        var applied = 0

        ThemeTransitionState.captureAndChange(laidOutView()) { applied++ }

        assertNotNull("capture should produce an overlay bitmap", ThemeTransitionState.overlay)
        assertNotNull(ThemeTransitionState.pendingChange)
        assertEquals(0, applied)
        assertFalse(ThemeTransitionState.animationDone)
    }

    @Test
    fun `captureAndChange is ignored while an overlay is already present`() {
        ThemeTransitionState.captureAndChange(laidOutView()) { }
        val first = ThemeTransitionState.overlay
        var secondApplied = 0

        ThemeTransitionState.captureAndChange(laidOutView()) { secondApplied++ }

        assertSame(first, ThemeTransitionState.overlay)
        assertEquals(0, secondApplied)
    }

    @Test
    fun `consumePendingChange applies the change exactly once`() {
        var applied = 0
        ThemeTransitionState.captureAndChange(laidOutView()) { applied++ }

        ThemeTransitionState.consumePendingChange()
        ThemeTransitionState.consumePendingChange()

        assertEquals(1, applied)
        assertNull(ThemeTransitionState.pendingChange)
    }

    @Test
    fun `clear drops state but leaves the captured bitmap drawable`() {
        var applied = 0
        ThemeTransitionState.captureAndChange(laidOutView()) { applied++ }
        val captured = requireNotNull(ThemeTransitionState.overlay)

        ThemeTransitionState.clear()

        assertNull(ThemeTransitionState.overlay)
        assertNull(ThemeTransitionState.pendingChange)
        assertEquals("clear must not apply a pending change", 0, applied)
        assertFalse(
            "clear must not recycle the bitmap — a frame in flight may still draw it",
            captured.isRecycled
        )
    }

    @Test
    fun `clear is safe with no overlay captured`() {
        ThemeTransitionState.clear()

        assertNull(ThemeTransitionState.overlay)
        assertNull(ThemeTransitionState.pendingChange)
        assertTrue(ThemeTransitionState.animationDone)
    }
}
