package io.github.selectionmenucontrol

import kotlin.math.max
import kotlin.math.min

/** Coordinates use the option viewport, with no list content padding. */
internal data class RuleDragBounds(val top: Float, val bottom: Float)

internal data class RuleDragCell(val key: String, val index: Int, val top: Float, val height: Float) {
    val center: Float get() = top + height / 2f
}

internal object RuleDragGeometry {
    /** Calibrate an animated pickup at its slot without losing either end of the drag region. */
    fun insertionCenter(
        paintedTop: Float,
        height: Float,
        pickupPaintedTop: Float,
        pickupSlotTop: Float,
        bounds: RuleDragBounds?,
    ): Float? {
        if (top(paintedTop, 0f, height, bounds) == null || !pickupPaintedTop.isFinite() || !pickupSlotTop.isFinite()) return null
        val firstTop = bounds!!.top
        val lastTop = bounds.bottom - height
        val origin = pickupPaintedTop.coerceIn(firstTop, lastTop)
        val slot = pickupSlotTop.coerceIn(firstTop, lastTop)
        // A released card can be painted at an edge while its slot is farther inside.
        // The pointer may leave that edge while the complete overlay remains clamped.
        val above = (origin - firstTop).takeIf { it > 0f } ?: height
        val below = (lastTop - origin).takeIf { it > 0f } ?: height
        val insertionTop = when {
            paintedTop < origin -> slot + (paintedTop - origin) * (slot - firstTop) / above
            paintedTop > origin -> slot + (paintedTop - origin) * (lastTop - slot) / below
            else -> slot
        }
        return insertionTop.coerceIn(firstTop, lastTop) + height / 2f
    }

    /** Visible cells may be a subset, but must belong to the current measured order. */
    fun matchesOrder(order: List<String>, visible: List<RuleDragCell>): Boolean =
        visible.isNotEmpty() && visible.all { order.getOrNull(it.index) == it.key }

    /** New gestures hit painted cards; insertion decisions below use measured slot positions. */
    fun hit(pointerY: Float, rowGap: Float, visible: List<RuleDragCell>): RuleDragCell? {
        if (!pointerY.isFinite() || !rowGap.isFinite() || rowGap < 0f) return null
        return visible.lastOrNull {
            it.top.isFinite() && it.height.isFinite() && it.height > rowGap * 2f
                && pointerY >= it.top + rowGap && pointerY <= it.top + it.height - rowGap
        }
    }

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
        if (!bounds.top.isFinite() || !bounds.bottom.isFinite()) return null
        val lastTop = bounds.bottom - height
        if (lastTop < bounds.top) return null
        return (pointerY - grabOffset).coerceIn(bounds.top, lastTop)
    }

    /** Hand off only to a measured, unchanged card that fits wholly inside the option region. */
    fun releaseTop(
        component: String,
        height: Float,
        order: List<String>,
        visible: List<RuleDragCell>,
        bounds: RuleDragBounds?,
    ): Float? {
        if (!matchesOrder(order, visible)) return null
        val source = visible.firstOrNull { it.key == component } ?: return null
        if (source.height != height) return null
        val bounded = top(source.top, 0f, height, bounds) ?: return null
        return source.top.takeIf { it == bounded }
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
