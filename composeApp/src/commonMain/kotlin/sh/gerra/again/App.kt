package sh.gerra.again

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import sh.gerra.again.platform.Camera
import sh.gerra.again.platform.Photos
import sh.gerra.again.ui.nav.LocalNavEntry
import sh.gerra.again.ui.nav.Navigator
import sh.gerra.again.ui.nav.Screen
import sh.gerra.again.ui.screens.CameraScreen
import sh.gerra.again.ui.screens.CompareScreen
import sh.gerra.again.ui.screens.HomeScreen
import sh.gerra.again.ui.theme.AgainTheme

/**
 * Root of the shared UI: three screens on a small back stack. [photos] and [camera] are supplied by
 * each platform entry point. [systemBack] lets a platform hook its own back affordance (Android's
 * button and predictive back gesture) into the navigator: it is composed with whether the app can
 * go back and what to do then.
 */
@Composable
internal fun App(
    photos: Photos,
    camera: Camera,
    navigator: Navigator = remember { Navigator() },
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    AgainTheme {
        // Surface sets the content colour for every Text below it and paints the background.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            systemBack(navigator.canGoBack) { navigator.pop() }
            val entry = navigator.current
            // One composition per entry, so a screen's remembered state never carries over to the next one.
            key(entry) {
                CompositionLocalProvider(LocalNavEntry provides entry) {
                    when (val screen = entry.screen) {
                        Screen.Home -> HomeScreen(photos, onPicked = { navigator.push(Screen.Camera(it)) })
                        is Screen.Camera -> CameraScreen(
                            reference = screen.reference,
                            camera = camera,
                            photos = photos,
                            onChangedPhoto = { navigator.replace(Screen.Camera(it)) },
                            onCaptured = { guide, photo -> navigator.push(Screen.Compare(guide, photo)) },
                        )
                        // Retake is back: the camera screen waited underneath, lined up as it was.
                        is Screen.Compare -> CompareScreen(screen.guide, screen.capture, photos, onRetake = { navigator.pop() })
                    }
                }
            }
        }
    }
}
