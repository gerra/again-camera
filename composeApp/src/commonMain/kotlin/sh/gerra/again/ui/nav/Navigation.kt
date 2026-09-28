package sh.gerra.again.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.ui.ScreenModel
import kotlin.reflect.KClass

internal sealed interface Screen {
    /** Choose an old photo. */
    data object Home : Screen

    /** The viewfinder with [reference] laid over it. */
    data class Camera(val reference: ReferencePhoto) : Screen

    /** Then and now, after the shutter: [capture] next to the old photo placed by [guide]. */
    data class Compare(val guide: Guide, val capture: CapturedPhoto) : Screen
}

/** One screen on the back stack, with the [ScreenModel]s that belong to it for as long as it is there. */
internal class NavEntry(val screen: Screen) {
    private val models = mutableMapOf<KClass<*>, ScreenModel>()

    /** The model of class [type] for this entry, made by [factory] the first time. */
    fun <T : ScreenModel> model(type: KClass<T>, factory: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return models.getOrPut(type, factory) as T
    }

    /** The model of class [type] this entry holds, or null while its screen has not made one. */
    fun <T : ScreenModel> peek(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return models[type] as T?
    }

    internal fun clear() {
        models.values.forEach { it.onCleared() }
        models.clear()
    }
}

/** The entry whose screen is being composed, or null outside the navigator. */
internal val LocalNavEntry = staticCompositionLocalOf<NavEntry?> { null }

/**
 * The back stack: Home, the camera over it, the comparison over that. Retaking pops back to the
 * camera, whose entry (and model) waited underneath with the same photo, lined up as it was left.
 */
internal class Navigator(start: Screen = Screen.Home) {
    val stack = mutableStateListOf(NavEntry(start))

    val current: NavEntry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack.add(NavEntry(screen))
    }

    /** Puts [screen] where the current one is, so going back from it reaches what came before. */
    fun replace(screen: Screen) {
        if (stack.size <= 1) return push(screen)
        stack.removeAt(stack.lastIndex).clear()
        stack.add(NavEntry(screen))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).clear()
        return true
    }
}
