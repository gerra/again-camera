package sh.gerra.again.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideTest {
    private val guide = Guide(ReferencePhoto("/photos/reference-1.jpg"))

    @Test
    fun startsHalfSeeThroughAndUnmoved() {
        assertEquals(0.5f, guide.opacity)
        assertEquals(OverlayTransform(), guide.transform)
        assertFalse(guide.isMoved)
    }

    @Test
    fun opacityStaysWithinBounds() {
        assertEquals(Guide.MIN_OPACITY, guide.withOpacity(0f).opacity)
        assertEquals(Guide.MIN_OPACITY, guide.withOpacity(-3f).opacity)
        assertEquals(Guide.MAX_OPACITY, guide.withOpacity(1f).opacity)
        assertEquals(0.7f, guide.withOpacity(0.7f).opacity)
        assertEquals(0.5f, guide.withOpacity(Float.NaN).opacity)
    }

    @Test
    fun resetRestoresScaleOffsetAndRotationButKeepsOpacity() {
        val moved = guide.withOpacity(0.3f).gestured(panX = 0.2f, panY = 0.1f, zoom = 2.5f, rotation = 12f)
        assertTrue(moved.isMoved)
        val reset = moved.resetAlignment()
        assertEquals(OverlayTransform(), reset.transform)
        assertEquals(0.3f, reset.opacity)
        assertEquals(guide.reference, reset.reference)
        assertFalse(reset.isMoved)
    }
}
