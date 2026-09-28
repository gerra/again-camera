package sh.gerra.again.comparison

import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.ReferencePhoto
import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonStateTest {
    private val guide = Guide(ReferencePhoto("/photos/reference-1.jpg"), opacity = 0.4f).gestured(0.1f, 0f, 1.2f, 3f)
    private val state = ComparisonState(guide, CapturedPhoto("/cache/again-1.jpg"))

    @Test
    fun theDividerStartsHalfway() = assertEquals(0.5f, state.divider)

    @Test
    fun theDividerStaysInsideTheFrame() {
        assertEquals(0f, state.withDivider(-0.5f).divider)
        assertEquals(1f, state.withDivider(1.5f).divider)
        assertEquals(1f, state.draggedBy(0.3f).draggedBy(0.3f).divider)
        assertEquals(0.2f, state.draggedBy(-0.3f).divider, 1e-6f)
        assertEquals(0.5f, state.withDivider(Float.NaN).divider)
    }

    @Test
    fun movingTheDividerKeepsBothPhotosAndTheAlignment() {
        val moved = state.withDivider(0.8f)
        assertEquals(guide, moved.guide)
        assertEquals(state.capture, moved.capture)
    }
}
