package com.splitfree.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.LayoutDirection

/**
 * Restrained, directional motion: one easing curve and three durations. Forward navigation slides in from
 * the trailing edge, back navigation from the leading edge; the transitions take the [LayoutDirection] so
 * "trailing" and "leading" follow the reading direction and mirror under RTL.
 */
object SfMotion {
    val Ease = CubicBezierEasing(0.22f, 0.8f, 0.25f, 1f)

    /** Press feedback, colour changes, status dots. */
    val Fast: Int = 160

    /** Screen transitions, tab panes, sheets. */
    val Base: Int = 220

    /** Large reveals (hero card, empty-state art). */
    val Slow: Int = 320

    /** +1 when the trailing edge is on the right (LTR), -1 when it is on the left (RTL). */
    private val LayoutDirection.trailingSign: Int
        get() = if (this == LayoutDirection.Ltr) 1 else -1

    private fun <T> spec(durationMillis: Int = Base) = tween<T>(durationMillis = durationMillis, easing = Ease)

    /** New destination entering on forward navigation, from the trailing edge. */
    fun forwardEnter(direction: LayoutDirection): EnterTransition =
        slideInHorizontally(spec()) { direction.trailingSign * it / 9 } + fadeIn(spec())

    /** Current destination leaving on forward navigation, towards the leading edge. */
    fun forwardExit(direction: LayoutDirection): ExitTransition =
        slideOutHorizontally(spec()) { -direction.trailingSign * it / 16 } + fadeOut(spec())

    /** Previous destination re-entering on back: instant return without shrink, fade or slide. */
    fun popEnter(direction: LayoutDirection = LayoutDirection.Ltr): EnterTransition = EnterTransition.None

    /** Popped destination leaving on back: instant return without shrink, fade or slide. */
    fun popExit(direction: LayoutDirection = LayoutDirection.Ltr): ExitTransition = ExitTransition.None
}
