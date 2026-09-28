package sh.gerra.again.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayTransformTest {
    @Test
    fun gesturesAccumulate() {
        val moved = OverlayTransform()
            .gestured(panX = 0.1f, panY = -0.2f, zoom = 2f, rotation = 10f)
            .gestured(panX = 0.05f, panY = 0f, zoom = 1.5f, rotation = 5f)
        assertEquals(3f, moved.scale, 1e-4f)
        assertEquals(0.15f, moved.offsetX, 1e-4f)
        assertEquals(-0.2f, moved.offsetY, 1e-4f)
        assertEquals(15f, moved.rotationDegrees, 1e-4f)
    }

    @Test
    fun scaleStaysWithinBounds() {
        val tiny = OverlayTransform().gestured(0f, 0f, zoom = 0.001f, rotation = 0f)
        assertEquals(OverlayTransform.MIN_SCALE, tiny.scale)
        val huge = OverlayTransform().gestured(0f, 0f, zoom = 1000f, rotation = 0f)
        assertEquals(OverlayTransform.MAX_SCALE, huge.scale)
        // Pinching back out from the floor works straight away: the excess was never stored.
        assertEquals(OverlayTransform.MIN_SCALE * 2, tiny.gestured(0f, 0f, zoom = 2f, rotation = 0f).scale, 1e-4f)
    }

    @Test
    fun theCentreCannotLeaveTheFrame() {
        val flung = OverlayTransform().gestured(panX = 5f, panY = -5f, zoom = 1f, rotation = 0f)
        assertEquals(OverlayTransform.MAX_OFFSET, flung.offsetX)
        assertEquals(-OverlayTransform.MAX_OFFSET, flung.offsetY)
    }

    @Test
    fun rotationWrapsAround() {
        assertEquals(-170f, OverlayTransform(rotationDegrees = 170f).gestured(0f, 0f, 1f, rotation = 20f).rotationDegrees, 1e-3f)
        assertEquals(170f, OverlayTransform(rotationDegrees = -170f).gestured(0f, 0f, 1f, rotation = -20f).rotationDegrees, 1e-3f)
        assertEquals(180f, OverlayTransform(rotationDegrees = 180f).constrained().rotationDegrees)
    }

    @Test
    fun brokenGestureValuesAreIgnored() {
        val start = OverlayTransform(scale = 2f, offsetX = 0.1f)
        assertEquals(start, start.gestured(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN))
    }
}
