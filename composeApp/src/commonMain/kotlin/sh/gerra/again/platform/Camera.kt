package sh.gerra.again.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import sh.gerra.again.domain.CapturedPhoto

/**
 * The phone's camera, as the shared UI sees it: whether the app may use it, the live preview through
 * the [Lens] of the UI's choosing, and (through [CameraController]) the shutter. Everything else —
 * how it is opened, when it is released — belongs to the platform: CameraX on Android, AVFoundation
 * on iOS.
 */
internal interface Camera {
    val permission: StateFlow<CameraPermission>

    /** Asks the user for the camera; the answer arrives through [permission]. */
    fun requestPermission()

    /** The system settings page for this app, for turning the camera back on after a refusal. */
    fun openSettings()

    /**
     * The live preview through [lens], filling [modifier] edge to edge and cropped to it. The camera
     * is opened when this enters the composition and released when it leaves, and a photograph shows
     * exactly what the preview does, no more: the front camera's preview is a mirror, as people expect
     * of a selfie, and so is its photograph. A change of [lens] switches cameras in place. [onStatus]
     * hears when it is ready, and again whenever it reopens or has switched.
     */
    @Composable
    fun Preview(modifier: Modifier, lens: Lens, onStatus: (CameraStatus) -> Unit)
}

internal enum class CameraPermission { NotAsked, Granted, Denied }

/** Which of the phone's cameras: the back one, for the world, or the front one, for yourself. */
internal enum class Lens {
    Back,
    Front,
    ;

    val other: Lens get() = if (this == Back) Front else Back
}

internal sealed interface CameraStatus {
    data object Opening : CameraStatus
    class Ready(val controller: CameraController) : CameraStatus
    /** No camera, or it could not be opened (another app holding it, a device policy). */
    data object Unavailable : CameraStatus
}

/** What the camera screen can ask of an open camera. */
internal interface CameraController {
    val hasFlash: Boolean

    /** Whether the phone has a front camera as well as a back one, so the lens is worth offering. */
    val canSwitchLens: Boolean

    fun setFlash(enabled: Boolean)

    /**
     * Takes a photograph with the camera itself — never a picture of the screen, so nothing drawn
     * over the preview can end up in it — and returns the file it was written to. Throws when the
     * camera fails.
     */
    suspend fun capture(): CapturedPhoto
}
