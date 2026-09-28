package sh.gerra.again

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.ui.screens.CompareMessage
import sh.gerra.again.ui.screens.CompareModel

@OptIn(ExperimentalCoroutinesApi::class)
class CompareModelTest {
    private val guide = Guide(ReferencePhoto("/photos/old.jpg")).gestured(0.1f, 0f, 1.3f, 0f)
    private val capture = CapturedPhoto("/cache/again-1.jpg")
    private val photos = TestPhotos()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun bothPhotosLoadWithTheDividerHalfway() = runTest {
        val state = CompareModel(guide, capture, photos).state.value
        assertTrue(state.old != null && state.new != null)
        assertEquals(0.5f, state.comparison.divider)
        assertEquals(guide, state.comparison.guide)
    }

    @Test
    fun thePhotoThatCannotBeReadIsSaidSo() = runTest {
        val state = CompareModel(guide, capture, TestPhotos(unreadable = setOf(capture.path))).state.value
        assertTrue(state.loadFailed)
    }

    @Test
    fun theDividerIsDraggedWithinTheFrame() = runTest {
        val model = CompareModel(guide, capture, photos)
        model.onDividerDragged(0.3f)
        assertEquals(0.8f, model.state.value.comparison.divider, 1e-6f)
        model.onDividerDragged(0.5f)
        assertEquals(1f, model.state.value.comparison.divider)
        model.onDividerSet(-1f)
        assertEquals(0f, model.state.value.comparison.divider)
    }

    @Test
    fun theNewPhotoIsSavedOnceAsItWasTaken() = runTest {
        val model = CompareModel(guide, capture, photos)
        model.onSave()
        model.onSave()
        assertEquals(listOf(capture), photos.saved)
        assertTrue(model.state.value.saved)
        assertEquals(CompareMessage.Saved, model.state.value.message)
    }

    @Test
    fun aFailedSaveCanBeTriedAgain() = runTest {
        photos.failSave = true
        val model = CompareModel(guide, capture, photos)
        model.onSave()
        assertEquals(CompareMessage.SaveFailed, model.state.value.message)
        assertFalse(model.state.value.saved)
        assertFalse(model.state.value.saving)

        photos.failSave = false
        model.onSave()
        assertEquals(listOf(capture), photos.saved)
        assertEquals(CompareMessage.Saved, model.state.value.message)
    }

    @Test
    fun sharingHandsOverTheNewPhotoAndKeepsTheSavedNote() = runTest {
        val model = CompareModel(guide, capture, photos)
        model.onSave()
        model.onShare()
        assertEquals(listOf(capture), photos.shared)
        assertEquals(CompareMessage.Saved, model.state.value.message)
    }
}
