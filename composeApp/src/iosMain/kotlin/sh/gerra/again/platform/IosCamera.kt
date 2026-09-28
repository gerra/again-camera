package sh.gerra.again.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * The iOS camera: the permission is real, the preview is not built yet, so the camera screen says
 * the camera cannot be opened. Android is the v1 platform.
 *
 * The AVFoundation version fits behind the same [Camera] without touching the shared UI:
 * - [Preview]: a `UIKitView` hosting an `AVCaptureVideoPreviewLayer` with `resizeAspectFill`, over
 *   an `AVCaptureSession` started when it enters the composition and stopped when it leaves;
 * - [CameraController.capture]: `AVCapturePhotoOutput.capturePhoto`, then the photo cropped to
 *   what the preview layer shows (`metadataOutputRectConverted(fromLayerRect:)`), as CameraX's
 *   viewport does on Android, and written as a JPEG;
 * - [CameraController.setFlash]: `AVCapturePhotoSettings.flashMode`.
 */
internal class IosCamera : Camera {
    private val _permission = MutableStateFlow(currentPermission())
    override val permission: StateFlow<CameraPermission> = _permission

    private fun currentPermission(): CameraPermission = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> CameraPermission.Granted
        AVAuthorizationStatusNotDetermined -> CameraPermission.NotAsked
        else -> CameraPermission.Denied
    }

    override fun requestPermission() {
        // Answers at once, without asking again, once the user has said no; Settings is the way back then.
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            _permission.value = if (granted) CameraPermission.Granted else CameraPermission.Denied
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    @Composable
    override fun Preview(modifier: Modifier, onStatus: (CameraStatus) -> Unit) {
        val status by rememberUpdatedState(onStatus)
        Box(modifier)
        LaunchedEffect(Unit) { status(CameraStatus.Unavailable) }
    }
}
