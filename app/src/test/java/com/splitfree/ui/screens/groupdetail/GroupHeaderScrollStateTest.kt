package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupHeaderScrollStateTest {
    @Test
    fun `short content cannot collapse a shared header`() {
        val state = GroupHeaderScrollState()
        state.measure(240)
        val list = list(overflow = 0, canScroll = false)
        assertEquals(0f, state.collapse(1000f, list), 0f)
        assertEquals(240, state.measure(240))
    }

    @Test
    fun `one pixel of real overflow cannot become a full header collapse`() {
        val state = GroupHeaderScrollState()
        state.measure(240)
        val list = list(overflow = 1)
        assertEquals(1f, state.collapse(1000f, list), 0f)
        assertEquals(0f, state.collapse(1000f, list), 0f)
        assertEquals(239, state.measure(240))
    }

    @Test
    fun `multiple drag callbacks cannot spend the same measured overflow twice`() {
        val state = GroupHeaderScrollState()
        state.measure(240)
        val list = list(overflow = 20)
        assertEquals(12f, state.collapse(12f, list), 0f)
        assertEquals(8f, state.collapse(12f, list), 0f)
        assertEquals(0f, state.collapse(12f, list), 0f)
        assertEquals(220, state.measure(240))
        every { list.layoutInfo } returns info(20)
        assertEquals(
            "A child remeasure at unchanged viewport cannot spend pending geometry again",
            0f,
            state.collapse(1000f, list),
            0f
        )
    }

    @Test
    fun `long content collapses at most the header and expands by actual consumed distance`() {
        val state = GroupHeaderScrollState()
        state.measure(240)
        val list = list(overflow = 1000)
        assertEquals(240f, state.collapse(1000f, list), 0f)
        assertEquals(0, state.measure(240))
        assertEquals(0f, state.collapse(1000f, list), 0f)
        assertEquals(100f, state.expand(100f), 0f)
        assertEquals(100, state.measure(240))
        assertEquals(140f, state.expand(1000f), 0f)
        assertEquals(240, state.measure(240))
    }

    @Test
    fun `small window always reserves its pane and configuration changes clamp safely`() {
        val state = GroupHeaderScrollState()
        assertEquals(100, state.measure(100))
        assertEquals(0f, state.expand(1000f), 0f)
        assertEquals(100f, state.collapse(1000f, list(1000)), 0f)
        assertEquals(0, state.measure(100))
        assertEquals(100f, state.expand(1000f), 0f)
        assertEquals(100, state.measure(100))
    }

    @Test
    fun `expansion does not create pending collapse on a smaller viewport`() {
        val state = GroupHeaderScrollState()
        state.measure(240)
        val list = list(20)
        assertEquals(20f, state.collapse(20f, list), 0f)
        every { list.layoutInfo } returns info(0, viewport = 420)
        assertEquals(0f, state.collapse(1f, list), 0f)
        assertEquals(20f, state.expand(20f), 0f)
        every { list.layoutInfo } returns info(20, viewport = 400)
        assertEquals(20f, state.collapse(20f, list), 0f)
    }

    private fun list(overflow: Int, canScroll: Boolean = true): LazyListState = mockk {
        every { canScrollForward } returns canScroll
        every { layoutInfo } returns info(overflow)
    }

    private fun info(overflow: Int, viewport: Int = 400): LazyListLayoutInfo {
        val last = mockk<LazyListItemInfo> {
            every { index } returns 0
            every { offset } returns 0
            every { size } returns viewport - 100 + overflow
        }
        return mockk {
            every { visibleItemsInfo } returns listOf(last)
            every { totalItemsCount } returns 1
            every { afterContentPadding } returns 100
            every { viewportEndOffset } returns viewport
        }
    }
}
