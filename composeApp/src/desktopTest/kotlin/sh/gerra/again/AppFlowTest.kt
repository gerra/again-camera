package sh.gerra.again

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.OverlayTransform
import sh.gerra.again.platform.CameraPermission
import sh.gerra.again.ui.nav.Navigator
import sh.gerra.again.ui.nav.Screen
import sh.gerra.again.ui.screens.CameraModel
import sh.gerra.again.ui.screens.CompareModel

/**
 * The whole flow on real files, with a camera whose photograph is a drawn scene: choose an old
 * photo, line it up, take the new one, compare, save, share, retake.
 */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private val dir = tempDir()
    private val old = sceneFile(dir, "old.jpg", 400, 300, then = true)

    @Test
    fun chooseLineUpTakeCompareAndRetake() = runComposeUiTest {
        val captured = sceneFile(dir, "new.jpg", 400, 300)
        val cleanBytes = captured.readBytes()
        val camera = TestCamera(onCapture = { CapturedPhoto(captured.absolutePath) })
        val photos = DesktopPhotos(home = File(dir, "home"), pickFile = { old })
        val navigator = Navigator()
        setContent { App(photos, camera, navigator) }

        onNodeWithText("Choose an old photo").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and isEnabled(), 5_000)
        val cameraEntry = navigator.current
        assertTrue(cameraEntry.screen is Screen.Camera)
        onNodeWithText("Reset").assertIsNotEnabled()

        // Line it up: a pinch on the old photo moves only the guide.
        onNodeWithContentDescription("Old photo over the camera", substring = true).performTouchInput {
            pinch(center - Offset(20f, 0f), center + Offset(20f, 0f), center - Offset(60f, 10f), center + Offset(60f, 10f))
        }
        onNodeWithText("Reset").assertIsEnabled()
        onNodeWithContentDescription("How clearly the old photo shows").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        val lined = cameraEntry.peek(CameraModel::class)!!.state.value.guide
        assertNotEquals(OverlayTransform(), lined.transform)
        assertEquals(0.3f, lined.opacity, 1e-3f)

        onNodeWithContentDescription("Take photo").performClick()
        waitUntilExactlyOneExists(hasText("Then"), 5_000)
        onNodeWithText("Now").assertExists()
        val compare = navigator.current.screen as Screen.Compare
        // The comparison gets the camera's photograph untouched, with the guide it was lined up with.
        assertEquals(CapturedPhoto(captured.absolutePath), compare.capture)
        assertContentEquals(cleanBytes, File(compare.capture.path).readBytes())
        assertEquals(lined, compare.guide)

        // The divider starts halfway and moves where it is dragged or set.
        val divider = onNodeWithContentDescription("Divider between then and now")
        divider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50% old photo"))
        divider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        assertEquals(0.8f, navigator.current.peek(CompareModel::class)!!.state.value.comparison.divider, 1e-3f)

        onNodeWithText("Save").performClick()
        waitUntilExactlyOneExists(hasText("Saved"), 5_000)
        assertContentEquals(cleanBytes, File(photos.saved, captured.name).readBytes())
        // The desktop harness has no share sheet, which is said in words.
        onNodeWithText("Share").performClick()
        onNodeWithText("Sharing isn't available right now.").assertExists()

        // Retake: back on the camera with the same photo, lined up as it was left.
        onNodeWithText("Retake").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and isEnabled(), 5_000)
        assertEquals(cameraEntry, navigator.current)
        assertEquals(lined, cameraEntry.peek(CameraModel::class)!!.state.value.guide)
    }

    @Test
    fun aFailedCaptureStaysOnTheCamera() = runComposeUiTest {
        val camera = TestCamera(onCapture = { error("camera closed") })
        val photos = DesktopPhotos(home = File(dir, "failing"), pickFile = { old })
        val navigator = Navigator()
        setContent { App(photos, camera, navigator) }

        onNodeWithText("Choose an old photo").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and isEnabled(), 5_000)
        onNodeWithContentDescription("Take photo").performClick()
        waitUntilExactlyOneExists(hasText("The photo couldn't be taken. Please try again."), 5_000)
        onNodeWithContentDescription("Take photo").assertIsEnabled()
        assertTrue(navigator.current.screen is Screen.Camera)
    }

    @Test
    fun aRefusedCameraExplainsAndOffersSettings() = runComposeUiTest {
        val camera = TestCamera(permission = CameraPermission.NotAsked)
        val photos = DesktopPhotos(home = File(dir, "refused"), pickFile = { old })
        setContent { App(photos, camera) }

        onNodeWithText("Choose an old photo").performClick()
        waitUntilExactlyOneExists(hasText("Allow the camera"), 5_000)
        // Asked straight away, once.
        assertEquals(1, camera.permissionRequests)
        onNodeWithText("Open settings").assertDoesNotExist()

        camera.permission.value = CameraPermission.Denied
        waitUntilExactlyOneExists(hasText("Open settings"), 5_000)
        onNodeWithText("Allow camera").performClick()
        assertEquals(2, camera.permissionRequests)
        onNodeWithText("Open settings").performClick()
        assertEquals(1, camera.settingsOpened)
        // No shutter to press until the camera is allowed.
        onNodeWithContentDescription("Take photo").assertDoesNotExist()

        camera.permission.value = CameraPermission.Granted
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and isEnabled(), 5_000)
    }

    @Test
    fun aFileThatIsNotAPhotoIsTurnedAway() = runComposeUiTest {
        val notAPhoto = File(dir, "notes.jpg").apply { writeText("not a picture") }
        val photos = DesktopPhotos(home = File(dir, "unreadable"), pickFile = { notAPhoto })
        val navigator = Navigator()
        setContent { App(photos, TestCamera(), navigator) }

        onNodeWithText("Choose an old photo").performClick()
        waitUntilExactlyOneExists(hasText("That photo couldn't be opened. Please choose another one."), 5_000)
        assertEquals(Screen.Home, navigator.current.screen)
    }

    @Test
    fun theLastPhotoCanBeContinuedWith() = runComposeUiTest {
        val home = File(dir, "returning")
        DesktopPhotos(home = home, pickFile = { old }).pickReference {}
        val navigator = Navigator()
        setContent { App(DesktopPhotos(home = home, pickFile = { null }), TestCamera(), navigator) }

        waitUntilExactlyOneExists(hasText("Continue with this photo"), 5_000)
        onNodeWithText("Continue with this photo").performClick()
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and isEnabled(), 5_000)
        assertTrue(navigator.current.screen is Screen.Camera)
    }

    private fun isEnabled() = SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled)
}
