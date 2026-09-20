package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import kotlin.math.roundToInt

internal object EmptyGroupScrollConnection : NestedScrollConnection

/** Shared vertical chrome budget. Never derives horizontal position from a pager page. */
internal class GroupHeaderScrollState {
    private var requestedCollapse by mutableFloatStateOf(0f)
    private var height = 0f
    private var budgetList: LazyListState? = null
    private var budgetViewport = 0
    private var pendingCollapse = 0f

    private val collapse: Float get() = requestedCollapse.coerceIn(0f, height)

    fun measure(headerHeight: Int): Int {
        height = headerHeight.toFloat()
        return (height - collapse).roundToInt()
    }

    fun collapse(delta: Float, list: LazyListState): Float {
        if (delta <= 0f || !list.canScrollForward) return 0f
        val info = list.layoutInfo
        if (budgetList !== list) {
            budgetList = list
            pendingCollapse = 0f
            budgetViewport = info.viewportEndOffset
        } else if (info.viewportEndOffset != budgetViewport) {
            pendingCollapse =
                (pendingCollapse - (info.viewportEndOffset - budgetViewport).coerceAtLeast(0)).coerceAtLeast(0f)
            budgetViewport = info.viewportEndOffset
        }
        val last = info.visibleItemsInfo.lastOrNull()
        // When the last row is laid out, collapse only enough to reveal its bottom + action clearance.
        val overflow = if (last != null && last.index == info.totalItemsCount - 1) {
            (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0).toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
        val consumed =
            minOf(delta, (height - collapse).coerceAtLeast(0f), (overflow - pendingCollapse).coerceAtLeast(0f))
        if (consumed > 0f) {
            pendingCollapse += consumed
            requestedCollapse = collapse + consumed
        }
        return consumed
    }

    fun expand(delta: Float): Float {
        val consumed = minOf(delta.coerceAtLeast(0f), collapse)
        if (consumed > 0f) {
            requestedCollapse = collapse - consumed
            pendingCollapse = (pendingCollapse - consumed).coerceAtLeast(0f)
        }
        return consumed
    }
}
