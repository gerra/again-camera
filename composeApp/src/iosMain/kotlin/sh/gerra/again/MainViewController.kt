package sh.gerra.again

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import sh.gerra.again.platform.IosCamera
import sh.gerra.again.platform.IosPhotos

/** Entry point used by the SwiftUI wrapper in iosApp. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val photos = remember { IosPhotos().apply { clearCaptures() } }
    val camera = remember { IosCamera() }
    App(photos, camera)
}
