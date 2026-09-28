package sh.gerra.again.platform

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.ReferencePhoto

/**
 * Photos on Android, with no storage permission at all: the old photo comes from the system photo
 * picker, which hands over only the one picture chosen, and the new one goes into the gallery
 * through MediaStore under Pictures/Again. The app's own copies live in its private storage.
 */
internal class AndroidPhotos(private val activity: ComponentActivity) : Photos {
    private var pending: ((PickResult) -> Unit)? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val callback = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) callback(PickResult.Cancelled) else activity.lifecycleScope.launch { callback(keepReference(uri)) }
    }

    /** The copy of the old photo, kept across launches for "Continue with this photo". */
    private val references get() = File(activity.filesDir, "reference")

    override fun pickReference(onResult: (PickResult) -> Unit) {
        pending = onResult
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /**
     * Copies the chosen photo into the app's storage, since the picker's permission to read it ends
     * with the activity. Only the latest is kept. Each copy gets a new name, so a path always means
     * the same picture.
     */
    private suspend fun keepReference(uri: Uri): PickResult = withContext(Dispatchers.IO) {
        references.mkdirs()
        val copy = File(references, "reference-${System.currentTimeMillis()}")
        try {
            val input = activity.contentResolver.openInputStream(uri) ?: throw IOException("The picker's photo cannot be opened")
            input.use { source -> copy.outputStream().use { source.copyTo(it) } }
            // Decoded once, small, to turn away anything that is not a picture before the camera opens.
            decode(copy, maxPx = 64)
        } catch (e: CancellationException) {
            copy.delete()
            throw e
        } catch (e: Exception) {
            copy.delete()
            return@withContext PickResult.Unreadable
        }
        references.listFiles()?.filter { it != copy }?.forEach { it.delete() }
        PickResult.Picked(ReferencePhoto(copy.absolutePath))
    }

    override suspend fun lastReference(): ReferencePhoto? = withContext(Dispatchers.IO) {
        references.listFiles()?.maxByOrNull { it.lastModified() }?.let { ReferencePhoto(it.absolutePath) }
    }

    override suspend fun load(path: String, maxPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        decode(File(path), maxPx).asImageBitmap()
    }

    /**
     * ImageDecoder applies the EXIF orientation itself, so a photo taken with the phone held sideways
     * (the camera's own JPEGs included, which CameraX writes unrotated with an orientation tag) comes
     * out upright. It is sampled down by powers of two while decoding until its long side is no
     * more than [maxPx], so a camera-sized photo is never held in memory whole.
     */
    private fun decode(file: File, maxPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / sample > maxPx) sample *= 2
            decoder.setTargetSampleSize(sample)
            // Software pixels: Compose draws them the same everywhere, alpha and all.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /**
     * Writes the new photo's bytes, exactly as the camera saved them (EXIF and all), into the gallery.
     * IS_PENDING hides the entry until it is complete; a failure removes it again.
     */
    override suspend fun save(photo: CapturedPhoto) = withContext(Dispatchers.IO) {
        val resolver = activity.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val details = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Again_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Again")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, details) ?: throw IOException("The gallery did not take the photo")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("The gallery entry cannot be written")
            output.use { target -> File(photo.path).inputStream().use { it.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        Unit
    }

    /** The share sheet with the new photo, readable by the app chosen there and only for that share. */
    override fun share(photo: CapturedPhoto) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.photos", File(photo.path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The grant travels with the clip data through the chooser to the app picked in it.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, null))
    }

    /** Deletes the new photos of earlier runs: the ones that mattered were saved or shared then. */
    fun clearCaptures() {
        captures(activity).listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Where the camera writes new photos; res/xml/file_paths.xml shares this folder and no other. */
        fun captures(context: Context) = File(context.cacheDir, "captures")
    }
}
