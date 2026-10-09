package io.github.selectionmenucontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuleScreenOrderTest {
    private fun row(component: String, fixed: Boolean = false, known: Boolean = true) =
        Processor(component, component, "", fixed, known)

    private val original = listOf(
        row("a"), row("fixed1", fixed = true), row("hidden"), row("b"),
        row("unknown", known = false), row("fixed2", fixed = true), row("c"),
    )

    @Test
    fun systemOrderKeepsFixedRowsMixedAtTheirOriginalPositions() {
        val displayed = applyOrder(original, emptyList())
        assertEquals(original, displayed)
        original.indices.forEach { assertSame(original[it], displayed[it]) }
    }

    @Test
    fun savedOrdinaryOrderFillsOnlyOrdinarySlots() {
        val displayed = applyOrder(original, listOf("c", "hidden", "a", "b"))
        assertEquals(listOf("c", "fixed1", "hidden", "a", "unknown", "fixed2", "b"), displayed.map { it.component })
        assertSame(original[1], displayed[1])
        assertSame(original[4], displayed[4])
        assertSame(original[5], displayed[5])
    }

    @Test
    fun staleFixedAndDuplicateRanksCannotGroupOrMoveLockedRows() {
        val displayed = applyOrder(original, listOf("fixed2", "missing", "c", "c", "fixed1", "unknown", "a"))
        assertEquals(listOf("c", "fixed1", "a", "hidden", "unknown", "fixed2", "b"), displayed.map { it.component })
    }

    @Test
    fun draggingAcrossMixedLockedRowsKeepsAllLockedSlots() {
        val displayed = applyOrder(original, listOf("a", "hidden", "b", "c"))
        val moved = moveVisibleProcessors(displayed, setOf("hidden"), "a", "c")
        assertEquals(listOf("b", "fixed1", "hidden", "c", "unknown", "fixed2", "a"), moved.map { it.component })
        listOf(1, 2, 4, 5).forEach { assertSame(displayed[it], moved[it]) }
        assertEquals(displayed, moveVisibleProcessors(moved, setOf("hidden"), "a", "b"))
    }

    @Test
    fun fixedHiddenOrUnknownSourcesAndTargetsCannotMove() {
        for (locked in listOf("fixed1", "hidden", "unknown", "fixed2")) {
            assertSame(original, moveVisibleProcessors(original, setOf("hidden"), locked, "b"))
            assertSame(original, moveVisibleProcessors(original, setOf("hidden"), "b", locked))
        }
    }

    @Test
    fun savingAndReopeningAMixedListPreservesItsOrderAndOffscreenRules() {
        val displayed = moveVisibleProcessors(original, setOf("hidden"), "a", "c")
        val fixed = original.filter { it.fixed }.mapTo(HashSet()) { it.component }
        val saved = mergeOrder(listOf("offscreen", "a", "hidden", "b", "c"), displayed.map { it.component }, fixed)
        assertEquals(listOf("offscreen", "b", "hidden", "c", "unknown", "a"), saved)
        assertEquals(displayed, applyOrder(original, saved))
    }

    @Test
    fun restoringDefaultOrderUsesTheCompleteSystemOrder() {
        val custom = applyOrder(original, listOf("c", "hidden", "b", "a"))
        assertEquals(listOf("c", "fixed1", "hidden", "b", "unknown", "fixed2", "a"), custom.map { it.component })
        assertEquals(original, applyOrder(original, emptyList()))
    }
}
