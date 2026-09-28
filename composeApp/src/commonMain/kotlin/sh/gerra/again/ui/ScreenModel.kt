package sh.gerra.again.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import sh.gerra.again.ui.nav.LocalNavEntry

/**
 * Lightweight state holder: survives recompositions, and also the screens pushed over its own, so
 * the camera screen is exactly as it was left when the user comes back from the comparison to
 * retake. Cancelled when its screen leaves the back stack.
 *
 * A model is a plain class that takes what it uses in its constructor, so a test builds one with
 * fakes and no UI.
 */
internal abstract class ScreenModel {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    open fun onCleared() = scope.cancel()
}

/**
 * The [ScreenModel] of class [T] for the screen being composed, made by [factory] the first time.
 * Inside the navigator it belongs to the screen's back-stack entry and lives as long as that does;
 * outside it (a test composing one screen) it lives as long as the composable.
 */
@Composable
internal inline fun <reified T : ScreenModel> rememberScreenModel(noinline factory: () -> T): T {
    val entry = LocalNavEntry.current
    if (entry != null) return remember(entry) { entry.model(T::class, factory) }
    val model = remember { factory() }
    DisposableEffect(model) { onDispose { model.onCleared() } }
    return model
}
