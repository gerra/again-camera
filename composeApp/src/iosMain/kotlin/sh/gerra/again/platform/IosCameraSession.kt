package sh.gerra.again.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.CGImageRepresentation
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.hasFlash
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectIntegral
import platform.CoreGraphics.CGRectIntersection
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import sh.gerra.again.domain.CapturedPhoto

/**
 * The view the preview is drawn in: an [AVCaptureVideoPreviewLayer] filling it and cropped to it,
 * turned with the screen. It takes no touches; the guide's gestures are Compose's.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CameraPreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val previewLayer = AVCaptureVideoPreviewLayer().apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        // Filling the frame means the picture reaches past it; only what is inside is the photograph.
        clipsToBounds = true
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Without the implicit animation, the picture would slide into its new size on every turn.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
        previewLayer.connection?.turnTo(videoOrientation())
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        previewLayer.connection?.turnTo(videoOrientation())
    }

    /** The way up of the screen, as the camera names it. */
    fun videoOrientation(): AVCaptureVideoOrientation = when (window?.windowScene?.interfaceOrientation) {
        UIInterfaceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeLeft
        UIInterfaceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeRight
        UIInterfaceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
        else -> AVCaptureVideoOrientationPortrait
    }
}

private fun AVCaptureConnection.turnTo(orientation: AVCaptureVideoOrientation) {
    if (isVideoOrientationSupported() && videoOrientation != orientation) videoOrientation = orientation
}

/**
 * One opening of the camera: an [AVCaptureSession] feeding the preview layer and a photo output,
 * from [show] until [close] stops it for good. iOS pauses the session by itself while the app is in
 * the background and resumes it on return. Switching to the other camera swaps the session's input
 * while it runs.
 *
 * The photograph is cropped to exactly what the preview layer shows — as CameraX's viewport does on
 * Android — so it has the frame's proportions, which are the old photo's. It is the camera's own
 * picture: nothing drawn on the screen, the guide least of all, can get into it. The front camera's
 * preview is a mirror, and its photograph is made one too, or what was lined up would come out the
 * other way round.
 *
 * Everything here runs on the main thread except the session's configuration, start and stop, which
 * block and go to [queue], and the cropping and writing of a photo.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCameraSession(private val onStatus: (CameraStatus) -> Unit) : CameraController {
    private val session = AVCaptureSession()
    private val output = AVCapturePhotoOutput()
    private val queue = dispatch_queue_create("sh.gerra.again.camera", null)
    private var device: AVCaptureDevice? = null
    private var input: AVCaptureDeviceInput? = null
    private var outputAdded = false
    private var lens = Lens.Back
    private var view: CameraPreviewView? = null
    private var observers: List<NSObjectProtocol> = emptyList()
    private var flash = false
    private var closed = false

    /** Held until their photo arrives: the photo output does not keep its delegates alive. */
    private val pending = mutableSetOf<PhotoDelegate>()

    /** The first call opens the camera in [view] through [lens]; the later ones only switch the lens. */
    fun show(view: CameraPreviewView, lens: Lens) {
        if (this.view == null) open(view, lens) else switchTo(lens)
    }

    private fun open(view: CameraPreviewView, lens: Lens) {
        this.view = view
        this.lens = lens
        view.previewLayer.session = session
        onStatus(CameraStatus.Opening)
        observers = listOf(
            // The camera failed while running (a hardware error, the media services restarting).
            observe(AVCaptureSessionRuntimeErrorNotification) { onStatus(CameraStatus.Unavailable) },
            observe(AVCaptureSessionWasInterruptedNotification) { notification ->
                val reason = (notification?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber)?.longValue
                // Going to the background is not a problem to tell anyone about; anything else (another
                // app on the camera beside this one, a phone call) is.
                onStatus(if (reason == AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground) CameraStatus.Opening else CameraStatus.Unavailable)
            },
            observe(AVCaptureSessionInterruptionEndedNotification) { onStatus(CameraStatus.Ready(this)) },
        )
        apply(lens, start = true)
    }

    /** The other camera, swapped into the running session; the view keeps showing it, now as the new one sees. */
    private fun switchTo(lens: Lens) {
        if (lens == this.lens || closed) return
        this.lens = lens
        onStatus(CameraStatus.Opening)
        apply(lens, start = false)
    }

    /** On [queue]: puts the camera for [lens] into the session, starts it if asked, and says how that went. */
    private fun apply(lens: Lens, start: Boolean) {
        dispatch_async(queue) {
            val configured = configure(lens)
            if (configured && start) session.startRunning()
            dispatch_async(dispatch_get_main_queue()) {
                if (closed) return@dispatch_async
                // A new input gives the preview layer a new connection, to be turned like the last one.
                this.view?.let { it.previewLayer.connection?.turnTo(it.videoOrientation()) }
                onStatus(if (configured && session.running) CameraStatus.Ready(this) else CameraStatus.Unavailable)
            }
        }
    }

    private fun observe(name: String?, onNotification: (NSNotification?) -> Unit): NSObjectProtocol =
        NSNotificationCenter.defaultCenter.addObserverForName(name, session, NSOperationQueue.mainQueue) { notification ->
            if (!closed) onNotification(notification)
        }

    /**
     * Puts the camera for [lens] — or the other one, on a device with just that — into the session in
     * place of whichever was there; false when there is none to use. Nothing changes when it is the
     * camera already in use.
     */
    private fun configure(lens: Lens): Boolean {
        val device = listOf(lens, lens.other).firstNotNullOfOrNull(::deviceFor) ?: return false
        if (device == this.device) return true
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return false
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto
        this.input?.let(session::removeInput)
        val added = session.canAddInput(input) && (outputAdded || session.canAddOutput(output))
        if (added) {
            session.addInput(input)
            if (!outputAdded) {
                session.addOutput(output)
                outputAdded = true
            }
        }
        session.commitConfiguration()
        this.device = device.takeIf { added }
        this.input = input.takeIf { added }
        return added
    }

    private fun deviceFor(lens: Lens): AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVMediaTypeVideo,
        if (lens == Lens.Back) AVCaptureDevicePositionBack else AVCaptureDevicePositionFront,
    )

    fun close() {
        closed = true
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers = emptyList()
        view?.previewLayer?.session = null
        view = null
        dispatch_async(queue) { session.stopRunning() }
    }

    override val hasFlash: Boolean get() = device?.hasFlash == true

    override val canSwitchLens: Boolean get() = Lens.entries.all { deviceFor(it) != null }

    override fun setFlash(enabled: Boolean) {
        flash = enabled
    }

    override suspend fun capture(): CapturedPhoto = withContext(Dispatchers.Main) {
        val view = view ?: error("The camera is closed")
        val connection = output.connectionWithMediaType(AVMediaTypeVideo)
        // Asking a stopped camera for a photo is an Objective-C exception, which would end the app.
        check(session.running && connection != null && connection.active && connection.enabled) { "The camera is not running" }
        connection.turnTo(view.videoOrientation())
        // What the preview shows, as a fraction of the camera's picture in the sensor's own orientation.
        val crop = view.previewLayer.metadataOutputRectOfInterestForRect(view.previewLayer.bounds)
        // The photograph is to be the mirror image exactly when the preview is (the front camera's,
        // which iOS mirrors by itself); the photo output's own picture is not, so the difference is
        // made up in the orientation the JPEG is written with.
        val mirrored = (view.previewLayer.connection?.videoMirrored == true) != connection.videoMirrored
        val settings = AVCapturePhotoSettings.photoSettings()
        val flashOn = flash && output.supportedFlashModes.any { (it as? NSNumber)?.longValue == AVCaptureFlashModeOn }
        settings.flashMode = if (flashOn) AVCaptureFlashModeOn else AVCaptureFlashModeOff
        val delegate = PhotoDelegate()
        pending += delegate
        val photo = try {
            suspendCancellableCoroutine { continuation ->
                delegate.onPhoto = { photo, error ->
                    if (photo != null && error == null) continuation.resume(photo)
                    else continuation.resumeWithException(IllegalStateException("The camera did not take the photo: ${error?.localizedDescription}"))
                }
                output.capturePhotoWithSettings(settings, delegate)
            }
        } finally {
            pending -= delegate
        }
        withContext(Dispatchers.Default) { writeCropped(photo, crop, mirrored) }
    }

    /**
     * The photo cut down to [crop] and written as a JPEG into [capturesDirectory]. The pixels stay
     * as the sensor wrote them and the JPEG says which way is up, as the camera's own file does, and
     * whether it is [mirrored].
     */
    private fun writeCropped(photo: AVCapturePhoto, crop: CValue<CGRect>, mirrored: Boolean): CapturedPhoto {
        val image = photo.CGImageRepresentation() ?: error("The camera returned no picture")
        val width = CGImageGetWidth(image).toDouble()
        val height = CGImageGetHeight(image).toDouble()
        val rect = crop.useContents { CGRectMake(origin.x * width, origin.y * height, size.width * width, size.height * height) }
        val bounded = CGRectIntersection(CGRectIntegral(rect), CGRectMake(0.0, 0.0, width, height))
        check(!CGRectIsEmpty(bounded)) { "Nothing of the photo is in the frame" }
        val cropped = CGImageCreateWithImageInRect(image, bounded) ?: error("The photo could not be cropped")
        try {
            val jpeg = UIImageJPEGRepresentation(UIImage.imageWithCGImage(cropped, 1.0, orientationOf(photo, mirrored)), JPEG_QUALITY)
                ?: error("The photo could not be encoded")
            val folder = capturesDirectory()
            NSFileManager.defaultManager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
            val path = "$folder/again-${(NSDate().timeIntervalSince1970 * 1000).toLong()}.jpg"
            check(jpeg.writeToFile(path, atomically = true)) { "The photo could not be written" }
            return CapturedPhoto(path)
        } finally {
            CGImageRelease(cropped)
        }
    }

    private class PhotoDelegate : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
        var onPhoto: (AVCapturePhoto?, NSError?) -> Unit = { _, _ -> }

        override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
            onPhoto(didFinishProcessingPhoto, error)
        }
    }
}

/**
 * How the camera said the picture is turned (its EXIF orientation), as UIKit names it — or, when
 * [mirrored], the mirror image of that: the same way up, left for right.
 */
private fun orientationOf(photo: AVCapturePhoto, mirrored: Boolean): UIImageOrientation {
    val exif = (photo.metadata[EXIF_ORIENTATION] as? NSNumber)?.intValue ?: 1
    // EXIF pairs each way up with its mirror image: 1 and 2 upright, 3 and 4 upside down, 6 and 5
    // turned one way, 8 and 7 the other.
    val seen = if (!mirrored) exif else when (exif) {
        1 -> 2
        2 -> 1
        3 -> 4
        4 -> 3
        5 -> 6
        6 -> 5
        7 -> 8
        8 -> 7
        else -> 2
    }
    return when (seen) {
        2 -> UIImageOrientation.UIImageOrientationUpMirrored
        3 -> UIImageOrientation.UIImageOrientationDown
        4 -> UIImageOrientation.UIImageOrientationDownMirrored
        5 -> UIImageOrientation.UIImageOrientationLeftMirrored
        6 -> UIImageOrientation.UIImageOrientationRight
        7 -> UIImageOrientation.UIImageOrientationRightMirrored
        8 -> UIImageOrientation.UIImageOrientationLeft
        else -> UIImageOrientation.UIImageOrientationUp
    }
}

/** kCGImagePropertyOrientation's value, as a plain key into the photo's metadata. */
private const val EXIF_ORIENTATION = "Orientation"

private const val JPEG_QUALITY = 0.92
