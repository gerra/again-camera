package sh.gerra.again

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.OverlayTransform
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.CameraStatus
import sh.gerra.again.platform.PickResult
import sh.gerra.again.ui.screens.CameraModel
import sh.gerra.again.ui.screens.CameraProblem

@OptIn(ExperimentalCoroutinesApi::class)
class CameraModelTest {
    private val reference = ReferencePhoto("/photos/old.jpg")
    private val camera = TestCamera()
    private val photos = TestPhotos()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun readyModel() = CameraModel(reference, photos).apply { onCameraStatus(CameraStatus.Ready(camera.controller)) }

    @Test
    fun theShutterWaitsForTheCameraAndTheOldPhoto() = runTest {
        val model = CameraModel(reference, photos)
        assertFalse(model.state.value.canCapture)
        model.onCapture { _, _ -> error("no camera yet") }
        assertEquals(0, camera.captures)
        model.onCameraStatus(CameraStatus.Ready(camera.controller))
        assertTrue(model.state.value.canCapture)
    }

    @Test
    fun aDoubleTapTakesOnePhoto() = runTest {
        val shot = CompletableDeferred<CapturedPhoto>()
        camera.onCapture = { shot.await() }
        val model = readyModel()
        val taken = mutableListOf<CapturedPhoto>()
        model.onCapture { _, photo -> taken += photo }
        model.onCapture { _, photo -> taken += photo }
        assertTrue(model.state.value.isCapturing)
        assertFalse(model.state.value.canCapture)
        shot.complete(CapturedPhoto("/cache/again-1.jpg"))
        assertEquals(1, camera.captures)
        assertEquals(listOf(CapturedPhoto("/cache/again-1.jpg")), taken)
        assertFalse(model.state.value.isCapturing)
    }

    @Test
    fun aFailedCaptureSaysSoAndLeavesTheShutterReady() = runTest {
        camera.onCapture = { error("camera closed") }
        val model = readyModel()
        model.onCapture { _, _ -> error("nothing was taken") }
        assertEquals(CameraProblem.CaptureFailed, model.state.value.problem)
        assertTrue(model.state.value.canCapture)
        // Said for a few seconds, then out of the way.
        advanceTimeBy(CameraModel.PASSING_PROBLEM_MS + 1)
        assertNull(model.state.value.problem)

        camera.onCapture = { CapturedPhoto("/cache/again-2.jpg") }
        var taken: CapturedPhoto? = null
        model.onCapture { _, photo -> taken = photo }
        assertEquals(CapturedPhoto("/cache/again-2.jpg"), taken)
        assertNull(model.state.value.problem)
        assertEquals(2, model.state.value.shots)
    }

    @Test
    fun thePhotoIsComparedWithTheGuideAsLinedUpAtTheShutter() = runTest {
        camera.onCapture = { CapturedPhoto("/cache/again-3.jpg") }
        val model = readyModel()
        model.onOpacityChanged(0.3f)
        model.onTransformChanged(panX = 0.1f, panY = -0.05f, zoom = 1.5f, rotation = 4f)
        var compared: Guide? = null
        model.onCapture { guide, _ -> compared = guide }
        assertEquals(Guide(reference, 0.3f, OverlayTransform(1.5f, 0.1f, -0.05f, 4f)), compared)
        // Nothing about the guide is lost for a retake.
        assertEquals(compared, model.state.value.guide)
    }

    @Test
    fun opacityAndAlignmentStayWithinBoundsAndReset() = runTest {
        val model = readyModel()
        model.onOpacityChanged(1f)
        assertEquals(Guide.MAX_OPACITY, model.state.value.guide.opacity)
        model.onTransformChanged(panX = 3f, panY = 0f, zoom = 100f, rotation = 0f)
        assertEquals(OverlayTransform(scale = OverlayTransform.MAX_SCALE, offsetX = OverlayTransform.MAX_OFFSET), model.state.value.guide.transform)
        model.onResetAlignment()
        assertEquals(OverlayTransform(), model.state.value.guide.transform)
        assertEquals(Guide.MAX_OPACITY, model.state.value.guide.opacity)
    }

    @Test
    fun theFlashChoiceCarriesOverWhenTheCameraReopens() = runTest {
        val model = readyModel()
        model.onFlashToggled()
        assertEquals(true, camera.flash)
        camera.flash = null
        model.onCameraStatus(CameraStatus.Opening)
        assertFalse(model.state.value.canCapture)
        model.onCameraStatus(CameraStatus.Ready(camera.controller))
        assertEquals(true, camera.flash)
    }

    @Test
    fun anUnreadableOldPhotoIsReported() = runTest {
        val model = CameraModel(reference, TestPhotos(unreadable = setOf(reference.path)))
        assertEquals(CameraProblem.ReferenceUnreadable, model.state.value.problem)
        model.onCameraStatus(CameraStatus.Ready(camera.controller))
        assertFalse(model.state.value.canCapture)
    }

    @Test
    fun anUnavailableCameraIsReportedUntilItOpens() = runTest {
        val model = CameraModel(reference, photos)
        model.onCameraStatus(CameraStatus.Unavailable)
        assertEquals(CameraProblem.CameraUnavailable, model.state.value.problem)
        advanceTimeBy(CameraModel.PASSING_PROBLEM_MS * 2)
        assertEquals(CameraProblem.CameraUnavailable, model.state.value.problem)
        model.onCameraStatus(CameraStatus.Ready(camera.controller))
        assertNull(model.state.value.problem)
    }

    @Test
    fun changingToAnUnreadablePhotoKeepsTheCurrentOne() = runTest {
        photos.pick = { PickResult.Unreadable }
        val model = readyModel()
        model.onChangePhoto { error("nothing new to open") }
        assertEquals(CameraProblem.PickedUnreadable, model.state.value.problem)
        assertEquals(reference, model.state.value.guide.reference)
    }
}
