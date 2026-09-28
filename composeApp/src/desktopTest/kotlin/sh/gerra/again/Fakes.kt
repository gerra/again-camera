package sh.gerra.again

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.Camera
import sh.gerra.again.platform.CameraController
import sh.gerra.again.platform.CameraPermission
import sh.gerra.again.platform.CameraStatus
import sh.gerra.again.platform.Photos
import sh.gerra.again.platform.PickResult

/** A camera whose shutter the test controls: [onCapture] decides what each photograph is, or throws. */
internal class TestCamera(
    permission: CameraPermission = CameraPermission.Granted,
    var onCapture: suspend () -> CapturedPhoto = { error("no capture expected") },
) : Camera {
    override val permission = MutableStateFlow(permission)
    var permissionRequests = 0
    var settingsOpened = 0
    var captures = 0
    var flash: Boolean? = null

    override fun requestPermission() {
        permissionRequests++
    }

    override fun openSettings() {
        settingsOpened++
    }

    val controller = object : CameraController {
        override val hasFlash = true
        override fun setFlash(enabled: Boolean) {
            flash = enabled
        }

        override suspend fun capture(): CapturedPhoto {
            captures++
            return onCapture()
        }
    }

    @Composable
    override fun Preview(modifier: Modifier, onStatus: (CameraStatus) -> Unit) {
        val status by rememberUpdatedState(onStatus)
        Box(modifier)
        LaunchedEffect(Unit) { status(CameraStatus.Ready(controller)) }
    }
}

/** Photos without files: every path loads as a small bitmap, unless it is in [unreadable]. */
internal class TestPhotos(
    var pick: () -> PickResult = { PickResult.Cancelled },
    private val unreadable: Set<String> = emptySet(),
) : Photos {
    val saved = mutableListOf<CapturedPhoto>()
    val shared = mutableListOf<CapturedPhoto>()
    var failSave = false
    var last: ReferencePhoto? = null

    override fun pickReference(onResult: (PickResult) -> Unit) = onResult(pick())
    override suspend fun lastReference() = last

    override suspend fun load(path: String, maxPx: Int): ImageBitmap {
        if (path in unreadable) error("not an image")
        return ImageBitmap(40, 30)
    }

    override suspend fun save(photo: CapturedPhoto) {
        if (failSave) error("disk full")
        saved += photo
    }

    override fun share(photo: CapturedPhoto) {
        shared += photo
    }
}

internal fun tempDir(): File = Files.createTempDirectory("again-test").toFile().apply { deleteOnExit() }

/** A JPEG on disk, drawn like the harness's camera scene. */
internal fun sceneFile(dir: File, name: String, width: Int = 400, height: Int = 300, then: Boolean = false): File =
    File(dir, name).apply { writeBytes(renderScene(width, height, then)) }
