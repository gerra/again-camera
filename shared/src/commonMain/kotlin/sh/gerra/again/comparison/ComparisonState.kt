package sh.gerra.again.comparison

import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide

/**
 * The old and new photographs in one frame, split by a vertical divider: the old one to its left,
 * the new one to its right. [divider] is where it stands, as a fraction of the frame's width. The
 * old photo is placed by the [guide] it was lined up with, so the comparison shows exactly what was
 * matched in the viewfinder.
 */
data class ComparisonState(
    val guide: Guide,
    val capture: CapturedPhoto,
    val divider: Float = START,
) {
    /** The divider moved to [fraction] of the width, kept inside the frame. */
    fun withDivider(fraction: Float): ComparisonState = copy(divider = if (fraction.isNaN()) divider else fraction.coerceIn(0f, 1f))

    /** The divider dragged by [fraction] of the width. */
    fun draggedBy(fraction: Float): ComparisonState = withDivider(divider + fraction)

    companion object {
        /** Halfway: as much of then as of now. */
        const val START = 0.5f
    }
}
