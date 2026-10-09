package io.github.selectionmenucontrol

import kotlin.math.max
import kotlin.math.min

/** Coordinates use the option viewport, with no list content padding. */
internal data class RuleDragBounds(val top: Float, val bottom: Float)

internal data class RuleDragCell(val key: String, val index: Int, val top: Float, val height: Float) {
    val center: Float get() = top + height / 2f
}

internal object RuleDragGeometry {
    fun bounds(
        viewportTop: Float,
        viewportBottom: Float,
        firstOptionTop: Float?,
        lastOptionBottom: Float?,
    ): RuleDragBounds? {
        if (!viewportTop.isFinite() || !viewportBottom.isFinite()) return null
        val top = max(viewportTop, firstOptionTop ?: viewportTop)
        val bottom = min(viewportBottom, lastOptionBottom ?: viewportBottom)
        return if (top.isFinite() && bottom.isFinite() && bottom > top) RuleDragBounds(top, bottom) else null
    }

    /** Clamp the complete item, including its outside spacing, rather than clipping its paint. */
    fun top(pointerY: Float, grabOffset: Float, height: Float, bounds: RuleDragBounds?): Float? {
        if (bounds == null || !pointerY.isFinite() || !grabOffset.isFinite() || !height.isFinite() || height <= 0f) return null
        val lastTop = bounds.bottom - height
        if (lastTop < bounds.top) return null
        return (pointerY - grabOffset).coerceIn(bounds.top, lastTop)
    }

    /** Cross item centers; locked rows are never insertion targets. */
    fun target(sourceIndex: Int, center: Float, visible: List<RuleDragCell>, movableKeys: Set<String>): String? {
        if (!center.isFinite()) return null
        val source = visible.firstOrNull { it.index == sourceIndex }
        val direction = when {
            source != null -> center.compareTo(source.center)
            visible.isEmpty() -> 0
            sourceIndex < visible.minOf { it.index } -> 1
            sourceIndex > visible.maxOf { it.index } -> -1
            else -> 0
        }
        return when {
            direction > 0 -> visible.filter { it.index > sourceIndex && it.key in movableKeys && it.center <= center }
                .maxByOrNull { it.index }?.key
            direction < 0 -> visible.filter { it.index < sourceIndex && it.key in movableKeys && it.center >= center }
                .minByOrNull { it.index }?.key
            else -> null
        }
    }
}

/** Refill movable slots only: hidden, fixed and unknown rows keep their exact positions. */
internal fun <T> moveRuleSlots(
    original: List<T>,
    key: (T) -> String,
    movable: (T) -> Boolean,
    fromKey: String,
    toKey: String,
): List<T> {
    val moving = original.filter(movable).toMutableList()
    val from = moving.indexOfFirst { key(it) == fromKey }
    val to = moving.indexOfFirst { key(it) == toKey }
    if (from < 0 || to < 0 || from == to) return original
    moving.add(to, moving.removeAt(from))
    val reordered = moving.iterator()
    return original.map { if (movable(it)) reordered.next() else it }
}
