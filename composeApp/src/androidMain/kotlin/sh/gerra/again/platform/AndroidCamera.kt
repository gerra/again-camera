package sh.gerra.again.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera as BoundCamera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as PreviewUseCase
import androidx.camera.core.UseCaseGroup
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
    override fun Preview(modifier: Modifier, onStatus: (CameraStatus) -> Unit) {
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
                    session.open(this)
                }
            },
            modifier = modifier,
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
 * display's rotation changes.
 */
private class CameraSession(
    private val activity: ComponentActivity,
    private val onStatus: (CameraStatus) -> Unit,
) : CameraController {
    private val preview = PreviewUseCase.Builder().build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .build()
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val displays = activity.getSystemService(DisplayManager::class.java)

    private var provider: ProcessCameraProvider? = null
    private var view: PreviewView? = null
    private var camera: BoundCamera? = null
    /** The preview's width and height and the display's rotation the use cases were last bound for. */
    private var boundFor: Triple<Int, Int, Int>? = null
    private var closed = false

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bind() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == view?.display?.displayId) bind()
        }
    }

    fun open(view: PreviewView) {
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
        val key = Triple(view.width, view.height, display.rotation)
        if (key == boundFor) return
        val viewPort = view.getViewPort(display.rotation) ?: return
        val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).firstOrNull {
            try {
                provider.hasCamera(it)
            } catch (e: Exception) {
                false
            }
        }
        if (selector == null) {
            onStatus(CameraStatus.Unavailable)
            return
        }
        preview.targetRotation = display.rotation
        imageCapture.targetRotation = display.rotation
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .build()
        try {
            provider.unbind(preview, imageCapture)
            camera = provider.bindToLifecycle(activity, selector, group)
            boundFor = key
            onStatus(CameraStatus.Ready(this))
        } catch (e: Exception) {
            // Another app holding the camera, a device policy, a combination this camera can't do.
            camera = null
            boundFor = null
            onStatus(CameraStatus.Unavailable)
        }
    }

    fun close() {
        closed = true
        view?.removeOnLayoutChangeListener(layoutListener)
        displays.unregisterDisplayListener(displayListener)
        provider?.unbind(preview, imageCapture)
        preview.setSurfaceProvider(null)
        view = null
        camera = null
    }

    override val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    override fun setFlash(enabled: Boolean) {
        imageCapture.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    /**
     * ImageCapture writes the JPEG straight from the camera into the app's cache. The callback comes
     * on the main thread, so no executor is created here to be leaked. Cancelling (the screen left
     * mid-capture) just stops waiting; the file, if it is still written, goes at the next launch.
     */
    override suspend fun capture(): CapturedPhoto {
        val folder = AndroidPhotos.captures(activity).apply { mkdirs() }
        val file = File(folder, "again-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(CapturedPhoto(file.absolutePath))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
    }
}
