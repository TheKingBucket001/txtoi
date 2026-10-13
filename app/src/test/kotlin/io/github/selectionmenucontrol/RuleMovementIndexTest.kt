package io.github.selectionmenucontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleMovementIndexTest {
    private val rows = listOf(
        Processor("fixed", "固定", "app", true, true),
        Processor("first", "普通一", "app", false, true),
        Processor("hidden", "已隐藏", "app", false, true),
        Processor("unknown", "未识别", "app", false, false),
        Processor("last", "普通二", "app", false, true),
    )

    @Test fun accessibleMovementUsesOnlyMovableSlotsWhileRetainingDisplayedPositions() {
        val index = RuleMovementIndex.create(rows, setOf("hidden"))
        assertEquals(listOf("first", "last"), index.components)
        assertEquals(mapOf("first" to 0, "last" to 1), index.positions)
        assertEquals(mapOf("fixed" to 0, "first" to 1, "hidden" to 2, "unknown" to 3, "last" to 4), index.displayedPositions)
        val reordered = moveVisibleProcessors(rows, setOf("hidden"), "last", index.components.first())
        assertEquals(listOf("fixed", "last", "hidden", "unknown", "first"), reordered.map { it.component })
        assertEquals(listOf("last", "first"), RuleMovementIndex.create(reordered, setOf("hidden")).components)
    }

    @Test fun refreshedMembershipAndUnhidingDoNotInventMovableFixedOrUnknownRows() {
        assertEquals(listOf("first", "hidden", "last"), RuleMovementIndex.create(rows, emptySet()).components)
        val changed = rows.filterNot { it.component == "first" } + Processor("new", "新项目", "app", false, true)
        assertEquals(listOf("last", "new"), RuleMovementIndex.create(changed, setOf("hidden")).components)
        assertEquals(emptyList<String>(), RuleMovementIndex.create(rows, setOf("first", "hidden", "last")).components)
    }
}
