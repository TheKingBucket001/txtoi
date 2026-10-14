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

    @Test
    fun aNewGripHitsThePaintedCardWhileInsertionStillUsesMeasuredSlots() {
        val measured = listOf(
            RuleDragCell("b", 0, 0f, 72f), RuleDragCell("c", 1, 72f, 72f),
            RuleDragCell("a", 2, 144f, 72f),
        )
        val paintedSiblings = listOf(
            RuleDragCell("b", 0, 72f, 72f), RuleDragCell("c", 1, 144f, 72f),
        )
        assertEquals("c", RuleDragGeometry.hit(170f, 3f, paintedSiblings)?.key)
        // A stationary dragged card must not move back merely because C is still animating away.
        assertNull(RuleDragGeometry.target(2, 156f, measured, setOf("a", "b", "c")))
        assertEquals("c", RuleDragGeometry.target(2, 156f, paintedSiblings, setOf("a", "b", "c")))
    }

    @Test
    fun spacingAndInvalidPaintCoordinatesAreNotGripHits() {
        assertNull(RuleDragGeometry.hit(1f, 3f, cells))
        assertNull(RuleDragGeometry.hit(71f, 3f, cells))
        assertNull(RuleDragGeometry.hit(Float.NaN, 3f, cells))
        assertNull(RuleDragGeometry.hit(20f, 3f, listOf(RuleDragCell("invalid", 0, Float.NaN, 72f))))
    }

    @Test
    fun pickingUpAnAnimatingCardKeepsItsLogicalSlotUntilTheFingerCrossesANewSlot() {
        val measured = listOf(
            RuleDragCell("b", 0, 0f, 72f), RuleDragCell("c", 1, 72f, 72f),
            RuleDragCell("a", 2, 144f, 72f),
        )
        // A is still drawn near its previous slot, but already owns logical slot 2.
        val paintedTop = 40f
        val bounds = RuleDragBounds(0f, 216f)
        val origin = RuleDragGeometry.insertionCenter(paintedTop, 72f, paintedTop, 144f, bounds)!!
        assertEquals(measured[2].center, origin, 0f)
        assertNull(RuleDragGeometry.target(2, origin, measured, setOf("a", "b", "c")))
        assertNull(RuleDragGeometry.target(2,
            RuleDragGeometry.insertionCenter(21f, 72f, paintedTop, 144f, bounds)!!, measured, setOf("a", "b", "c")))
        assertEquals("c", RuleDragGeometry.target(2,
            RuleDragGeometry.insertionCenter(20f, 72f, paintedTop, 144f, bounds)!!, measured, setOf("a", "b", "c")))
    }

    @Test
    fun animatedPickupsCanStillReachBothEndsOfShortAndTallRegions() {
        for (height in listOf(72f, 144f)) {
            val bounds = RuleDragBounds(0f, 500f)
            val lastTop = bounds.bottom - height
            for ((painted, slot) in listOf(40f to 200f, 240f to 0f, 40f to lastTop)) {
                assertEquals(slot + height / 2f,
                    RuleDragGeometry.insertionCenter(painted, height, painted, slot, bounds)!!, 0f)
                assertEquals(height / 2f,
                    RuleDragGeometry.insertionCenter(0f, height, painted, slot, bounds)!!, 0f)
                assertEquals(bounds.bottom - height / 2f,
                    RuleDragGeometry.insertionCenter(lastTop, height, painted, slot, bounds)!!, 0f)
            }
        }
        assertNull(RuleDragGeometry.insertionCenter(0f, 101f, 0f, 0f, RuleDragBounds(0f, 100f)))
        assertNull(RuleDragGeometry.insertionCenter(0f, 72f, Float.NaN, 0f, RuleDragBounds(0f, 100f)))
    }

    @Test
    fun regrabbingAReleasedCardAtAnEdgeDoesNotReverseTheOrderUntilTheFingerActuallyMoves() {
        val bounds = RuleDragBounds(0f, 144f)
        val measured = listOf(RuleDragCell("b", 0, 0f, 72f), RuleDragCell("a", 1, 72f, 72f))
        val keys = setOf("a", "b")
        assertNull(RuleDragGeometry.target(1,
            RuleDragGeometry.insertionCenter(0f, 72f, 0f, 72f, bounds)!!, measured, keys))
        assertNull(RuleDragGeometry.target(1,
            RuleDragGeometry.insertionCenter(-71f, 72f, 0f, 72f, bounds)!!, measured, keys))
        assertEquals("b", RuleDragGeometry.target(1,
            RuleDragGeometry.insertionCenter(-72f, 72f, 0f, 72f, bounds)!!, measured, keys))
        // Returning to the pickup point after a MOVE restores the original logical decision.
        assertEquals(measured[1].center, RuleDragGeometry.insertionCenter(0f, 72f, 0f, 72f, bounds)!!, 0f)
        assertEquals(108f, RuleDragGeometry.insertionCenter(144f, 72f, 72f, 0f, bounds)!!, 0f)
        assertEquals(0f, RuleDragGeometry.top(-72f, 0f, 72f, bounds)!!, 0f)
    }

    @Test
    fun scrollingMovesTargetsPastTheViewportPickupRatherThanMovingTheInsertionCenter() {
        val bounds = RuleDragBounds(0f, 216f)
        val before = listOf(RuleDragCell("a", 0, 0f, 72f), RuleDragCell("b", 1, 72f, 72f))
        val center = RuleDragGeometry.insertionCenter(60f, 72f, 0f, 0f, bounds)!!
        assertEquals(96f, center, 0f)
        assertNull(RuleDragGeometry.target(0, center, before, setOf("a", "b")))
        val scrolled = before.map { it.copy(top = it.top - 24f) }
        assertEquals("b", RuleDragGeometry.target(0, center, scrolled, setOf("a", "b")))
        for (pickup in listOf(0f, 40f, 144f)) {
            for (rawTop in listOf(-100f, 0f, 80f, 144f, 500f)) {
                assertEquals(RuleDragGeometry.top(rawTop, 0f, 72f, bounds)!! + 36f,
                    RuleDragGeometry.insertionCenter(rawTop, 72f, pickup, pickup, bounds)!!, 0.001f)
            }
        }
    }

    @Test
    fun anUnequalHeightMoveAcrossALockedSlotDoesNotOscillateAtTheCrossingPoint() {
        val before = listOf(
            RuleDragCell("a", 0, 0f, 72f), RuleDragCell("locked", 1, 72f, 200f),
            RuleDragCell("b", 2, 272f, 144f),
        )
        val after = listOf(
            RuleDragCell("b", 0, 0f, 144f), RuleDragCell("locked", 1, 144f, 200f),
            RuleDragCell("a", 2, 344f, 72f),
        )
        val moving = setOf("a", "b")
        assertNull(RuleDragGeometry.target(0, 343f, before, moving))
        assertEquals("b", RuleDragGeometry.target(0, 344f, before, moving))
        repeat(10) { assertNull(RuleDragGeometry.target(2, 344f, after, moving)) }
        assertEquals("b", RuleDragGeometry.target(2, 72f, after, moving))
    }

    @Test
    fun releaseWaitsForTheReorderedLayoutAndRequiresTheSourceCard() {
        val order = listOf("b", "a", "c")
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("a", 0, 0f, 72f), RuleDragCell("b", 1, 72f, 72f)), RuleDragBounds(0f, 216f)))
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order, emptyList(), RuleDragBounds(0f, 216f)))
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("c", 2, 0f, 72f)), RuleDragBounds(0f, 216f)))
        assertEquals(72f, RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("b", 0, 0f, 72f), RuleDragCell("a", 1, 72f, 72f)), RuleDragBounds(0f, 216f))!!, 0f)
    }

    @Test
    fun releaseHandsOffOnlyAtAnUnchangedFullyVisibleCard() {
        val order = listOf("a")
        val bounds = RuleDragBounds(0f, 100f)
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("a", 0, 0f, 80f)), bounds))
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("a", 0, -1f, 72f)), bounds))
        assertNull(RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("a", 0, 29f, 72f)), bounds))
        assertEquals(28f, RuleDragGeometry.releaseTop("a", 72f, order,
            listOf(RuleDragCell("a", 0, 28f, 72f)), bounds)!!, 0f)
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
