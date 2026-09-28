package sh.gerra.again.platform

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.ReferencePhoto

/** The photo files the app reads and writes, and the ways it hands them to the rest of the phone. */
internal interface Photos {
    /**
     * Opens the system photo picker for the old photo. The one chosen is copied into the app's own
     * storage, so it can be opened again later; [onResult] runs on the main thread.
     */
    fun pickReference(onResult: (PickResult) -> Unit)

    /** The old photo chosen last time, while the app's copy of it is still there. */
    suspend fun lastReference(): ReferencePhoto?

    /**
     * The photo at [path] decoded upright (its EXIF orientation applied) and no larger than
     * [maxPx] on its long side. Throws when it is not a picture the platform can read.
     */
    suspend fun load(path: String, maxPx: Int = DISPLAY_PX): ImageBitmap

    /** Copies the new photo into the phone's gallery. Throws when it could not be written. */
    suspend fun save(photo: CapturedPhoto)

    /** Opens the system share sheet with the new photo. Throws when there is no way to share. */
    fun share(photo: CapturedPhoto)

    companion object {
        /** Enough for a full-screen photo on any phone without holding a camera-sized bitmap. */
        const val DISPLAY_PX = 2048
        const val THUMBNAIL_PX = 640
    }
}

internal sealed interface PickResult {
    data class Picked(val photo: ReferencePhoto) : PickResult
    data object Cancelled : PickResult
    /** Something was chosen, but it is not a picture the app can open. */
    data object Unreadable : PickResult
}

/** [Photos.load], or null when the file cannot be read. */
internal suspend fun Photos.loadOrNull(path: String, maxPx: Int = Photos.DISPLAY_PX): ImageBitmap? = try {
    load(path, maxPx)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
