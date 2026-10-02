package sh.gerra.again.ui.screens

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.CameraController
import sh.gerra.again.platform.CameraStatus
import sh.gerra.again.platform.Lens
import sh.gerra.again.platform.Photos
import sh.gerra.again.platform.PickResult
import sh.gerra.again.platform.loadOrNull
import sh.gerra.again.ui.ScreenModel

internal enum class CameraProblem {
    /** The old photo could not be opened, so there is nothing to line up with. */
    ReferenceUnreadable,
    CameraUnavailable,
    CaptureFailed,
    /** A different photo was chosen from here and could not be opened; the current one stays. */
    PickedUnreadable,
}

internal data class CameraUiState(
    val guide: Guide,
    /** The old photo, decoded; null while it loads. */
    val reference: ImageBitmap? = null,
    val cameraReady: Boolean = false,
    /** The camera in use: the back one until the user turns it on themselves for a selfie. */
    val lens: Lens = Lens.Back,
    /** Whether there is another camera to turn to. */
    val canSwitchLens: Boolean = false,
    val hasFlash: Boolean = false,
    val flash: Boolean = false,
    val isCapturing: Boolean = false,
    /** Counts presses of the shutter, for the blink that answers each one. */
    val shots: Int = 0,
    val problem: CameraProblem? = null,
) {
    val canCapture: Boolean get() = cameraReady && reference != null && !isCapturing
}

/**
 * The camera screen's state: the guide (the old photo, how see-through it is and where it has been
 * moved), which camera is in use, and the shutter. It knows the camera only as a [CameraController],
 * so all of it runs in a test without one. The guide and the lens are kept when the comparison is
 * pushed over this screen, so a retake starts from the same alignment, through the same camera.
 */
internal class CameraModel(reference: ReferencePhoto, private val photos: Photos) : ScreenModel() {
    private val _state = MutableStateFlow(CameraUiState(Guide(reference)))
    val state: StateFlow<CameraUiState> = _state
    private var controller: CameraController? = null
    private var passingProblem: Job? = null

    init {
        scope.launch {
            val image = photos.loadOrNull(reference.path)
            _state.update { if (image == null) it.copy(problem = CameraProblem.ReferenceUnreadable) else it.copy(reference = image) }
        }
    }

    fun onCameraStatus(status: CameraStatus) {
        when (status) {
            is CameraStatus.Ready -> {
                controller = status.controller
                status.controller.setFlash(_state.value.flash)
                _state.update {
                    it.copy(
                        cameraReady = true,
                        canSwitchLens = status.controller.canSwitchLens,
                        hasFlash = status.controller.hasFlash,
                        problem = it.problem.takeUnless { p -> p == CameraProblem.CameraUnavailable },
                    )
                }
            }
            CameraStatus.Opening -> {
                controller = null
                _state.update { it.copy(cameraReady = false) }
            }
            CameraStatus.Unavailable -> {
                controller = null
                _state.update { it.copy(cameraReady = false, problem = CameraProblem.CameraUnavailable) }
            }
        }
    }

    fun onOpacityChanged(value: Float) = _state.update { it.copy(guide = it.guide.withOpacity(value)) }

    /** One step of a gesture on the old photo: the pan as fractions of the frame, the zoom as a factor, the twist in degrees. */
    fun onTransformChanged(panX: Float, panY: Float, zoom: Float, rotation: Float) =
        _state.update { it.copy(guide = it.guide.gestured(panX, panY, zoom, rotation)) }

    fun onResetAlignment() = _state.update { it.copy(guide = it.guide.resetAlignment()) }

    fun onFlashToggled() {
        val flash = !_state.value.flash
        controller?.setFlash(flash)
        _state.update { it.copy(flash = flash) }
    }

    /**
     * The other camera: the front one for a selfie, or back again. The preview switches to it and
     * reports being ready anew; the guide stays as it was lined up. Not while a photo is being taken,
     * which the switch would spoil.
     */
    fun onLensToggled() {
        if (_state.value.isCapturing) return
        _state.update { it.copy(lens = it.lens.other) }
    }

    /**
     * A problem that passes: said for a few seconds, then out of the way of the viewfinder. The
     * lasting ones (no old photo, no camera) stay for as long as they are true.
     */
    private fun showPassing(problem: CameraProblem) {
        passingProblem?.cancel()
        _state.update { it.copy(problem = problem) }
        passingProblem = scope.launch {
            delay(PASSING_PROBLEM_MS)
            _state.update { if (it.problem == problem) it.copy(problem = null) else it }
        }
    }

    /**
     * Takes the photograph through the camera alone; [onCaptured] gets it with the guide it was lined
     * up with. Ignored while a capture is already under way, so a double tap takes one photo. A
     * failure leaves the screen as it was, ready to try again.
     */
    fun onCapture(onCaptured: (Guide, CapturedPhoto) -> Unit) {
        val controller = controller ?: return
        if (!_state.value.canCapture) return
        // What was lined up when the shutter was pressed is what the photo is compared against.
        val guide = _state.value.guide
        passingProblem?.cancel()
        _state.update { it.copy(isCapturing = true, shots = it.shots + 1, problem = null) }
        scope.launch {
            val photo = try {
                controller.capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isCapturing = false) }
                showPassing(CameraProblem.CaptureFailed)
                return@launch
            }
            _state.update { it.copy(isCapturing = false) }
            onCaptured(guide, photo)
        }
    }

    /** "Change photo": the picker, straight from the camera. [onPicked] gets the new photo; cancelling stays here. */
    fun onChangePhoto(onPicked: (ReferencePhoto) -> Unit) {
        photos.pickReference { result ->
            when (result) {
                is PickResult.Picked -> onPicked(result.photo)
                PickResult.Unreadable -> showPassing(CameraProblem.PickedUnreadable)
                PickResult.Cancelled -> Unit
            }
        }
    }

    companion object {
        const val PASSING_PROBLEM_MS = 5_000L
    }
}
