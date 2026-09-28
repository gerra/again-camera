package sh.gerra.again

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.Density
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import sh.gerra.again.platform.CameraPermission
import sh.gerra.again.ui.nav.Navigator

/**
 * The README's screenshots, taken on the desktop harness at phone size: the drawn scene stands in
 * for the camera, and a sepia version of it for the old photo. Written to build/screenshots, or to
 * `-Pagain.screenshotDir=<dir>` (relative to the repository root).
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val out = File(System.getProperty("again.screenshotDir")).apply { mkdirs() }
    private val dir = tempDir()

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.phone(camera: sh.gerra.again.platform.Camera, photos: DesktopPhotos) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(SCALE)) { App(photos, camera, Navigator()) }
    }

    private fun ComposeUiTest.awaitCamera() =
        waitUntilExactlyOneExists(hasContentDescription("Take photo") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled), 5_000)

    @Test
    fun portrait() = runDesktopComposeUiTest((412 * SCALE).toInt(), (892 * SCALE).toInt()) {
        val old = sceneFile(dir, "old.jpg", 900, 1200, then = true)
        val photos = DesktopPhotos(home = File(dir, "portrait"), pickFile = { old })
        phone(FakeCamera(File(dir, "captures")), photos)
        shot("01-home")
        onNodeWithText("Choose an old photo").performClick()
        awaitCamera()
        shot("02-camera")
        onNodeWithContentDescription("Take photo").performClick()
        waitUntilExactlyOneExists(hasText("Then"), 5_000)
        shot("03-compare")
        onNodeWithText("Save").performClick()
        waitUntilExactlyOneExists(hasText("Saved"), 5_000)
        shot("04-saved")
    }

    @Test
    fun returning() = runDesktopComposeUiTest((412 * SCALE).toInt(), (892 * SCALE).toInt()) {
        val old = sceneFile(dir, "old.jpg", 900, 1200, then = true)
        val home = File(dir, "returning")
        DesktopPhotos(home = home, pickFile = { old }).pickReference {}
        phone(FakeCamera(File(dir, "captures")), DesktopPhotos(home = home, pickFile = { null }))
        waitUntilExactlyOneExists(hasText("Continue with this photo"), 5_000)
        shot("05-home-returning")
    }

    @Test
    fun permission() = runDesktopComposeUiTest((412 * SCALE).toInt(), (892 * SCALE).toInt()) {
        val old = sceneFile(dir, "old.jpg", 900, 1200, then = true)
        phone(TestCamera(permission = CameraPermission.Denied), DesktopPhotos(home = File(dir, "permission"), pickFile = { old }))
        onNodeWithText("Choose an old photo").performClick()
        waitUntilExactlyOneExists(hasText("Open settings"), 5_000)
        shot("06-camera-permission")
    }

    @Test
    fun landscape() = runDesktopComposeUiTest((892 * SCALE).toInt(), (412 * SCALE).toInt()) {
        val old = sceneFile(dir, "old.jpg", 1200, 900, then = true)
        phone(FakeCamera(File(dir, "captures")), DesktopPhotos(home = File(dir, "landscape"), pickFile = { old }))
        onNodeWithText("Choose an old photo").performClick()
        awaitCamera()
        shot("07-camera-landscape")
        onNodeWithContentDescription("Take photo").performClick()
        waitUntilExactlyOneExists(hasText("Then"), 5_000)
        shot("08-compare-landscape")
    }

    private companion object {
        const val SCALE = 2f
    }
}
