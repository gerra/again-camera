package sh.gerra.again.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
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
 * The camera through AVFoundation: the permission, and an [IosCameraSession] for each time the
 * preview is shown. iOS ends the app when its camera access is changed in Settings, so the
 * permission read at launch holds for as long as the app runs.
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
        val session = remember { IosCameraSession { status(it) } }
        DisposableEffect(session) {
            // Lining a photo up takes a while; the screen stays on while the camera is.
            UIApplication.sharedApplication.idleTimerDisabled = true
            onDispose {
                UIApplication.sharedApplication.idleTimerDisabled = false
                session.close()
            }
        }
        UIKitView(
            factory = { CameraPreviewView().also(session::open) },
            modifier = modifier,
            // Drawn under the Compose layers, which keep every touch: the guide's gestures are over it.
            properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
        )
    }
}
