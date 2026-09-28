package sh.gerra.again.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import sh.gerra.again.domain.CapturedPhoto

/**
 * The phone's camera, as the shared UI sees it: whether the app may use it, the live preview, and
 * (through [CameraController]) the shutter. Everything else — which lens, how it is opened, when it
 * is released — belongs to the platform: CameraX on Android, AVFoundation on iOS.
 */
internal interface Camera {
    val permission: StateFlow<CameraPermission>

    /** Asks the user for the camera; the answer arrives through [permission]. */
    fun requestPermission()

    /** The system settings page for this app, for turning the camera back on after a refusal. */
    fun openSettings()

    /**
     * The live preview, filling [modifier] edge to edge and cropped to it. The camera is opened when
     * this enters the composition and released when it leaves, and a photograph shows exactly what
     * the preview does, no more. [onStatus] hears when it is ready, and again whenever it reopens.
     */
    @Composable
    fun Preview(modifier: Modifier, onStatus: (CameraStatus) -> Unit)
}

internal enum class CameraPermission { NotAsked, Granted, Denied }

internal sealed interface CameraStatus {
    data object Opening : CameraStatus
    class Ready(val controller: CameraController) : CameraStatus
    /** No camera, or it could not be opened (another app holding it, a device policy). */
    data object Unavailable : CameraStatus
}

/** What the camera screen can ask of an open camera. */
internal interface CameraController {
    val hasFlash: Boolean

    fun setFlash(enabled: Boolean)

    /**
     * Takes a photograph with the camera itself — never a picture of the screen, so nothing drawn
     * over the preview can end up in it — and returns the file it was written to. Throws when the
     * camera fails.
     */
    suspend fun capture(): CapturedPhoto
}
