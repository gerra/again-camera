package sh.gerra.again.platform

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.posix.memcpy
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.ReferencePhoto

/**
 * Photos on iOS: the old photo from the system photo picker, which runs out of process and hands
 * back only the picture chosen, so the app needs no access to the library; the new one into the
 * library with add-only access; the share sheet for sharing.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPhotos : Photos {
    // Keep a strong reference: UIKit only holds the delegate weakly.
    private var delegate: PickerDelegate? = null
    private val main: CoroutineScope = MainScope()
    private val files = NSFileManager.defaultManager

    private val references: String
        get() = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/reference"

    override fun pickReference(onResult: (PickResult) -> Unit) {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration)
        delegate = PickerDelegate { bytes, chosen ->
            delegate = null
            main.launch {
                onResult(
                    when {
                        !chosen -> PickResult.Cancelled
                        bytes == null -> PickResult.Unreadable
                        else -> keepReference(bytes)
                    },
                )
            }
        }
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    /** A copy of the chosen photo in the app's own storage; only the latest is kept. */
    private suspend fun keepReference(bytes: ByteArray): PickResult = withContext(Dispatchers.IO) {
        if (runCatching { decodeUpright(bytes, maxPx = 64) }.isFailure) return@withContext PickResult.Unreadable
        files.createDirectoryAtPath(references, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$references/reference-${(NSDate().timeIntervalSince1970 * 1000).toLong()}"
        if (!bytes.toNSData().writeToFile(path, atomically = true)) return@withContext PickResult.Unreadable
        referenceFiles().filter { it != path }.forEach { files.removeItemAtPath(it, error = null) }
        PickResult.Picked(ReferencePhoto(path))
    }

    private fun referenceFiles(): List<String> =
        files.contentsOfDirectoryAtPath(references, error = null).orEmpty().map { "$references/$it" }

    override suspend fun lastReference(): ReferencePhoto? = withContext(Dispatchers.IO) {
        // Named by the time they were chosen, and the same number of digits for a very long while.
        referenceFiles().maxOrNull()?.let(::ReferencePhoto)
    }

    override suspend fun load(path: String, maxPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        val data = NSData.dataWithContentsOfFile(path) ?: error("No photo at $path")
        decodeUpright(data.toByteArray(), maxPx)
    }

    override suspend fun save(photo: CapturedPhoto) {
        val status = suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { continuation.resume(it) }
        }
        check(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) { "No access to the photo library" }
        val saved = suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                { PHAssetCreationRequest.creationRequestForAssetFromImageAtFileURL(NSURL.fileURLWithPath(photo.path)) },
                completionHandler = { success: Boolean, _: NSError? -> continuation.resume(success) },
            )
        }
        check(saved) { "The photo library did not take the photo" }
    }

    /**
     * Deletes the new photos of earlier runs. They are kept only by saving or sharing them, and
     * nothing on screen points at one after a restart.
     */
    fun clearCaptures() {
        files.removeItemAtPath(capturesDirectory(), error = null)
    }

    override fun share(photo: CapturedPhoto) {
        val top = topViewController() ?: error("Nothing on screen to share from")
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(photo.path)), applicationActivities = null)
        // An iPad shows the sheet as a popover, which needs somewhere to point.
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, animated = true, completion = null)
    }

    /** [onPicked] gets the photo's bytes (null when they cannot be read) and whether anything was chosen. */
    private class PickerDelegate(private val onPicked: (ByteArray?, Boolean) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, null)
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return onPicked(null, false)
            provider.loadDataRepresentationForTypeIdentifier(IMAGE_UTI) { data: NSData?, _: NSError? ->
                onPicked(data?.toByteArray(), true)
            }
        }
    }
}

/** Where the camera writes new photos: the app's caches, which iOS may also empty when space runs low. */
internal fun capturesDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String) + "/captures"

/**
 * What the picked photo is asked for as. The UTI rather than UTTypeImage, whose binding is nullable
 * while this never is.
 */
private const val IMAGE_UTI = "public.image"

/** Whatever is on top right now, so a sheet is presented from the visible screen. */
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return ByteArray(size).apply { usePinned { memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length) } }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
