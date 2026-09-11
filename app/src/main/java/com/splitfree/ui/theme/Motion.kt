package com.splitfree.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Restrained, directional motion: one easing curve and three durations. Forward navigation slides in from
 * the trailing edge, back navigation from the leading edge.
 */
object SfMotion {
    val Ease = CubicBezierEasing(0.22f, 0.8f, 0.25f, 1f)

    /** Press feedback, colour changes, status dots. */
    val Fast: Int = 160

    /** Screen transitions, tab panes, sheets. */
    val Base: Int = 220

    /** Large reveals (hero card, empty-state art). */
    val Slow: Int = 320

    private fun <T> spec() = tween<T>(durationMillis = Base, easing = Ease)

    /** New destination entering on forward navigation. */
    val forwardEnter: EnterTransition = slideInHorizontally(spec()) { it / 9 } + fadeIn(spec())

    /** Current destination leaving on forward navigation. */
    val forwardExit: ExitTransition = slideOutHorizontally(spec()) { -it / 16 } + fadeOut(spec())

    /** Previous destination re-entering on back. */
    val popEnter: EnterTransition = slideInHorizontally(spec()) { -it / 13 } + fadeIn(spec())

    /** Popped destination leaving on back. */
    val popExit: ExitTransition = slideOutHorizontally(spec()) { it / 11 } + fadeOut(spec())
}
