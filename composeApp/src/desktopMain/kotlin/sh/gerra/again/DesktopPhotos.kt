package sh.gerra.again

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.Photos
import sh.gerra.again.platform.PickResult
import sh.gerra.again.platform.decodeUpright
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.home_choose

/**
 * The harness's photos, under [home]: the old photo is picked with an AWT file dialog (or
 * [pickFile], in tests), saving copies into [home]/saved, and there is nothing to share with.
 */
internal class DesktopPhotos(
    private val home: File = File(System.getProperty("user.home"), ".again-dev"),
    private val pickFile: () -> File? = ::showFileDialog,
) : Photos {
    private val references = File(home, "reference")
    val captures = File(home, "captures")
    val saved = File(home, "saved")

    override fun pickReference(onResult: (PickResult) -> Unit) {
        val file = pickFile() ?: return onResult(PickResult.Cancelled)
        onResult(keepReference(file.readBytes()))
    }

    /** Keeps a copy of the chosen photo, and only the latest one, like the phone does. */
    private fun keepReference(bytes: ByteArray): PickResult {
        if (runCatching { decodeUpright(bytes, 64) }.isFailure) return PickResult.Unreadable
        references.mkdirs()
        val copy = File(references, "reference-${System.currentTimeMillis()}")
        copy.writeBytes(bytes)
        references.listFiles()?.filter { it != copy }?.forEach { it.delete() }
        return PickResult.Picked(ReferencePhoto(copy.absolutePath))
    }

    override suspend fun lastReference(): ReferencePhoto? = withContext(Dispatchers.IO) {
        references.listFiles()?.maxByOrNull { it.lastModified() }?.let { ReferencePhoto(it.absolutePath) }
    }

    override suspend fun load(path: String, maxPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        decodeUpright(File(path).readBytes(), maxPx)
    }

    override suspend fun save(photo: CapturedPhoto) = withContext(Dispatchers.IO) {
        saved.mkdirs()
        File(photo.path).copyTo(File(saved, File(photo.path).name), overwrite = true)
        Unit
    }

    override fun share(photo: CapturedPhoto) = throw UnsupportedOperationException("The desktop harness has no share sheet")
}

private fun showFileDialog(): File? {
    val dialog = FileDialog(null as Frame?, runBlocking { getString(Res.string.home_choose) }, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> listOf(".jpg", ".jpeg", ".png", ".webp").any { name.endsWith(it, ignoreCase = true) } }
    dialog.isVisible = true
    return dialog.files.firstOrNull { it.isFile }
}
