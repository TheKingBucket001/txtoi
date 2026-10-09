package io.github.selectionmenucontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDragGeometryTest {
    @Test
    fun shortListEndsAtItsLastOptionRatherThanAtTheEmptyViewport() {
        val bounds = RuleDragGeometry.bounds(0f, 900f, 0f, 360f)!!
        assertEquals(RuleDragBounds(0f, 360f), bounds)
        assertEquals(0f, RuleDragGeometry.top(-900f, 25f, 72f, bounds)!!, 0f)
        assertEquals(288f, RuleDragGeometry.top(1800f, 25f, 72f, bounds)!!, 0f)
    }

    @Test
    fun scrolledLongListUsesOnlyTheVisibleOptionRegion() {
        assertEquals(RuleDragBounds(0f, 500f), RuleDragGeometry.bounds(0f, 500f, null, null))
        assertEquals(RuleDragBounds(0f, 500f), RuleDragGeometry.bounds(0f, 500f, -40f, 540f))
        assertEquals(RuleDragBounds(0f, 420f), RuleDragGeometry.bounds(0f, 500f, null, 420f))
    }

    @Test
    fun allPointerPositionsKeepTheCompleteCardInsideTheBounds() {
        for (viewportHeight in listOf(80f, 170f, 500f)) {
            for (height in listOf(1f, 72f, viewportHeight)) {
                val bounds = RuleDragBounds(12f, 12f + viewportHeight)
                for (grabOffset in listOf(0f, height / 2f, height)) {
                    for (pointer in listOf(-10000f, 0f, 12f, 77.3f, 500f, 10000f)) {
                        val top = RuleDragGeometry.top(pointer, grabOffset, height, bounds)!!
                        assertTrue("top=$top bounds=$bounds", top >= bounds.top)
                        assertTrue("bottom=${top + height} bounds=$bounds", top + height <= bounds.bottom)
                    }
                }
            }
        }
    }

    @Test
    fun grabbingWithinTheCardDoesNotChangeItsInitialPosition() {
        val bounds = RuleDragBounds(0f, 700f)
        assertEquals(216f, RuleDragGeometry.top(247f, 31f, 72f, bounds)!!, 0f)
    }

    @Test
    fun anOptionLargerThanItsRegionOrInvalidGeometryCannotStartDragging() {
        assertNull(RuleDragGeometry.top(10f, 3f, 101f, RuleDragBounds(0f, 100f)))
        assertNull(RuleDragGeometry.top(Float.NaN, 3f, 50f, RuleDragBounds(0f, 100f)))
        assertNull(RuleDragGeometry.top(10f, Float.POSITIVE_INFINITY, 50f, RuleDragBounds(0f, 100f)))
        assertNull(RuleDragGeometry.top(10f, 3f, 0f, RuleDragBounds(0f, 100f)))
        assertNull(RuleDragGeometry.bounds(0f, 100f, 100f, 80f))
    }

    private val cells = (0..5).map { RuleDragCell("row$it", it, it * 72f, 72f) }

    @Test
    fun aRowMovesOnlyAfterItsCenterCrossesAnEligibleTargetCenter() {
        val keys = setOf("row1", "row4", "row5")
        assertNull(RuleDragGeometry.target(1, 323f, cells, keys))
        assertEquals("row4", RuleDragGeometry.target(1, 324f, cells, keys))
        assertEquals("row5", RuleDragGeometry.target(1, 410f, cells, keys))
        assertEquals("row1", RuleDragGeometry.target(5, 107f, cells, keys))
    }

    @Test
    fun fixedHiddenAndUnknownRowsAreNeverTargets() {
        val keys = setOf("row1", "row5")
        assertNull(RuleDragGeometry.target(1, 324f, cells, keys))
        assertEquals("row5", RuleDragGeometry.target(1, 396f, cells, keys))
        assertEquals("row1", RuleDragGeometry.target(5, -100f, cells, keys))
    }

    @Test
    fun scrollingAwayFromTheSourceStillSelectsAnEligibleVisibleTarget() {
        assertEquals("row5", RuleDragGeometry.target(1, 430f, cells.drop(3), setOf("row4", "row5")))
        assertEquals("row1", RuleDragGeometry.target(5, 50f, cells.take(3), setOf("row1")))
    }

    private data class Entry(val key: String, val movable: Boolean)
    private val entries = listOf(
        Entry("fixed", false), Entry("a", true), Entry("hidden", false),
        Entry("unknown", false), Entry("b", true), Entry("c", true),
    )

    @Test
    fun movingAcrossHiddenAndUnknownRowsLeavesTheirOriginalSlotsUntouched() {
        val moved = moveRuleSlots(entries, Entry::key, Entry::movable, "a", "c")
        assertEquals(listOf("fixed", "b", "hidden", "unknown", "c", "a"), moved.map { it.key })
        assertSame(entries[0], moved[0])
        assertSame(entries[2], moved[2])
        assertSame(entries[3], moved[3])
        assertEquals(entries, moveRuleSlots(moved, Entry::key, Entry::movable, "a", "b"))
    }

    @Test
    fun lockedSourcesAndTargetsCannotChangeTheOrder() {
        assertSame(entries, moveRuleSlots(entries, Entry::key, Entry::movable, "hidden", "b"))
        assertSame(entries, moveRuleSlots(entries, Entry::key, Entry::movable, "a", "unknown"))
        assertSame(entries, moveRuleSlots(entries, Entry::key, Entry::movable, "missing", "a"))
        assertSame(entries, moveRuleSlots(entries, Entry::key, Entry::movable, "b", "b"))
    }
}
