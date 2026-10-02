package sh.gerra.again.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera as BoundCamera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as PreviewUseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.gerra.again.domain.CapturedPhoto

/** The camera through CameraX: the permission, and a [CameraSession] for each time the preview is shown. */
internal class AndroidCamera(private val activity: ComponentActivity) : Camera {
    private val _permission = MutableStateFlow(CameraPermission.NotAsked)
    override val permission: StateFlow<CameraPermission> = _permission

    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        _permission.value = if (granted) CameraPermission.Granted else CameraPermission.Denied
    }

    private fun granted() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Reads the permission afresh: at launch, and on every return to the app, since Settings may have changed it. */
    fun refreshPermission() {
        if (granted()) {
            _permission.value = CameraPermission.Granted
        } else if (_permission.value == CameraPermission.Granted) {
            _permission.value = CameraPermission.Denied
        }
    }

    override fun requestPermission() {
        if (granted()) _permission.value = CameraPermission.Granted else request.launch(Manifest.permission.CAMERA)
    }

    override fun openSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
    }

    @Composable
    override fun Preview(modifier: Modifier, lens: Lens, onStatus: (CameraStatus) -> Unit) {
        val status by rememberUpdatedState(onStatus)
        val session = remember { CameraSession(activity) { status(it) } }
        DisposableEffect(session) { onDispose { session.close() } }
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    // A TextureView: it clips, fades and animates with the Compose layers above it.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // Lining a photo up takes a while; the screen stays on while the camera is.
                    keepScreenOn = true
                }
            },
            modifier = modifier,
            // Once the view exists, and again whenever the lens changes.
            update = { view -> session.show(view, lens) },
        )
    }
}

/**
 * One opening of the camera: a [PreviewUseCase] for the viewfinder and an [ImageCapture] for the
 * shutter, bound to the activity's lifecycle — so CameraX closes the camera whenever the app is in
 * the background and reopens it on return — until [close] releases them for good.
 *
 * Both use cases share a viewport taken from the [PreviewView], which is shaped like the old photo:
 * the photograph is cropped by the camera to exactly what the preview shows. The capture is the
 * camera's own JPEG; nothing on the screen, the guide least of all, can get into it.
 *
 * Both are turned with the display, so the photo comes out the way the screen showed it. The
 * activity handles its own rotation, so the session re-binds when the preview's size or the
 * display's rotation changes, and likewise when the [lens] does: the use cases are simply bound to
 * the other camera.
 */
private class CameraSession(
    private val activity: ComponentActivity,
    private val onStatus: (CameraStatus) -> Unit,
) : CameraController {
    private val preview = PreviewUseCase.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(MAX_CAPTURE_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build(),
        )
        .build()
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val displays = activity.getSystemService(DisplayManager::class.java)

    private var provider: ProcessCameraProvider? = null
    private var view: PreviewView? = null
    private var camera: BoundCamera? = null
    /** What the use cases were last bound for; null while they are not. */
    private var boundFor: Binding? = null
    private var closed = false

    /** The camera asked for. The other one stands in on a phone that lacks it. */
    var lens = Lens.Back
        set(value) {
            if (field == value) return
            field = value
            bind()
        }

    /** The preview's width and height, the display's rotation and the camera the use cases were bound for. */
    private data class Binding(val width: Int, val height: Int, val rotation: Int, val lens: Lens)

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bind() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == view?.display?.displayId) bind()
        }
    }

    /** The first call opens the camera in [view] through [lens]; the later ones only switch the lens. */
    fun show(view: PreviewView, lens: Lens) {
        if (this.view == null) open(view)
        this.lens = lens
    }

    private fun open(view: PreviewView) {
        this.view = view
        preview.setSurfaceProvider(view.surfaceProvider)
        view.addOnLayoutChangeListener(layoutListener)
        // A null handler delivers on the main thread, where everything here runs.
        displays.registerDisplayListener(displayListener, null)
        onStatus(CameraStatus.Opening)
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (closed) return@addListener
            provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "CameraX could not be started", e)
                onStatus(CameraStatus.Unavailable)
                return@addListener
            }
            bind()
        }, mainExecutor)
    }

    private fun bind() {
        val provider = provider ?: return
        val view = view ?: return
        val display = view.display ?: return
        if (closed || view.width == 0 || view.height == 0) return
        // The camera asked for, or the other one on a phone with just that (a front camera alone, say).
        val bound = listOf(lens, lens.other).firstOrNull(::has)
        if (bound == null) {
            onStatus(CameraStatus.Unavailable)
            return
        }
        val key = Binding(view.width, view.height, display.rotation, bound)
        if (key == boundFor) return
        val viewPort = view.getViewPort(display.rotation) ?: return
        preview.targetRotation = display.rotation
        imageCapture.targetRotation = display.rotation
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .build()
        try {
            provider.unbind(preview, imageCapture)
            camera = provider.bindToLifecycle(activity, bound.selector, group)
            boundFor = key
            onStatus(CameraStatus.Ready(this))
        } catch (e: Exception) {
            // Another app holding the camera, a device policy, a combination this camera can't do.
            Log.e(TAG, "The camera could not be bound", e)
            camera = null
            boundFor = null
            onStatus(CameraStatus.Unavailable)
        }
    }

    private fun has(lens: Lens): Boolean = try {
        provider?.hasCamera(lens.selector) == true
    } catch (e: Exception) {
        false
    }

    fun close() {
        closed = true
        view?.removeOnLayoutChangeListener(layoutListener)
        displays.unregisterDisplayListener(displayListener)
        provider?.unbind(preview, imageCapture)
        preview.setSurfaceProvider(null)
        view = null
        camera = null
        boundFor = null
    }

    override val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    override val canSwitchLens: Boolean get() = Lens.entries.all(::has)

    override fun setFlash(enabled: Boolean) {
        imageCapture.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    /**
     * ImageCapture writes the JPEG straight from the camera into the app's cache. The callback comes
     * on the main thread, so no executor is created here to be leaked. Cancelling (the screen left
     * mid-capture) just stops waiting; the file, if it is still written, goes at the next launch.
     *
     * The front camera's preview is a mirror, as [PreviewView] shows it, and so the photograph is
     * made one too, or what was lined up would come out the other way round: CameraX flips it through
     * the JPEG's orientation tag, with the pixels as the sensor gave them, as it does the turning.
     */
    override suspend fun capture(): CapturedPhoto {
        val folder = AndroidPhotos.captures(activity).apply { mkdirs() }
        val file = File(folder, "again-${System.currentTimeMillis()}.jpg")
        val mirrored = boundFor?.lens == Lens.Front
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirrored })
            .build()
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(CapturedPhoto(file.absolutePath))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // The screen only says the photo was not taken; the reason, for a bug report, goes to the log.
                        Log.e(TAG, "The photo could not be taken (ImageCapture error ${exception.imageCaptureError})", exception)
                        file.delete()
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
    }
}

private const val TAG = "AndroidCamera"

private val Lens.selector: CameraSelector
    get() = when (this) {
        Lens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
        Lens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

/**
 * The largest photograph asked of the camera, about 20 megapixels: the usual full resolution of a
 * phone camera fits under it, and the 48 to 200 megapixel modes of the newest sensors do not.
 * CameraX crops the photo to the frame by decoding the cropped region into a bitmap and encoding
 * it as a JPEG again, and at those sizes the bitmap alone is hundreds of megabytes, more than the
 * app is allowed, so the capture ended in an out-of-memory error instead of a photo. CameraX
 * compares sizes edge by edge, so a 4:3 size this wide or tall or smaller is kept, and the
 * largest of them is taken.
 */
private val MAX_CAPTURE_SIZE = Size(5184, 3888)
