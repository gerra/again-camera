package sh.gerra.again

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.stringResource
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.app_name

/**
 * The desktop development harness: the whole app in a phone-sized window, with a drawn scene in
 * place of the camera. `./gradlew :composeApp:run -Pagain.android=false`
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name), state = rememberWindowState(width = 420.dp, height = 860.dp)) {
        val photos = remember { DesktopPhotos() }
        val camera = remember { FakeCamera(photos.captures) }
        App(photos, camera)
    }
}
