package sh.gerra.again

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.platform.Camera
import sh.gerra.again.platform.CameraController
import sh.gerra.again.platform.CameraPermission
import sh.gerra.again.platform.CameraStatus
import sh.gerra.again.platform.Lens

/**
 * The harness's camera: a drawn scene in place of a lens. Like the real one, its photograph is the
 * scene alone, drawn afresh at the preview's size, never a picture of the screen. Its front camera
 * shows the scene as a mirror would, and photographs it that way too, as a phone's does.
 */
internal class FakeCamera(private val captures: File) : Camera {
    override val permission = MutableStateFlow(CameraPermission.Granted)

    override fun requestPermission() {
        permission.value = CameraPermission.Granted
    }

    override fun openSettings() = Unit

    @Composable
    override fun Preview(modifier: Modifier, lens: Lens, onStatus: (CameraStatus) -> Unit) {
        val status by rememberUpdatedState(onStatus)
        val controller = remember { Controller() }
        SideEffect { controller.lens = lens }
        Canvas(modifier.onSizeChanged { controller.size = it }) { drawScene(mirrored = lens == Lens.Front) }
        // Switching cameras is instant here; a real one reports being ready again once it has.
        LaunchedEffect(controller, lens) { status(CameraStatus.Ready(controller)) }
    }

    private inner class Controller : CameraController {
        var size = IntSize(1200, 900)
        var lens = Lens.Back
        override val hasFlash = true
        override val canSwitchLens = true
        override fun setFlash(enabled: Boolean) = Unit

        override suspend fun capture(): CapturedPhoto = withContext(Dispatchers.IO) {
            captures.mkdirs()
            val file = File(captures, "again-${System.currentTimeMillis()}.jpg")
            file.writeBytes(renderScene(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), mirrored = lens == Lens.Front))
            CapturedPhoto(file.absolutePath)
        }
    }
}

/** The scene at [width] × [height], as a JPEG; [mirrored] as the front camera shows and takes it. */
internal fun renderScene(width: Int, height: Int, then: Boolean = false, mirrored: Boolean = false): ByteArray {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { drawScene(then, mirrored) }
    return Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
}

/**
 * A house under a sky; [then] draws it as it was, in sepia, with the tree still small, and
 * [mirrored] draws it left to right, as a mirror would.
 */
private fun DrawScope.drawScene(then: Boolean = false, mirrored: Boolean = false) {
    if (mirrored) {
        scale(scaleX = -1f, scaleY = 1f) { drawScene(then) }
        return
    }
    val w = size.width
    val h = size.height
    val tint = { c: Color -> if (then) sepia(c) else c }
    drawRect(Brush.verticalGradient(listOf(tint(Color(0xFF7DB7E8)), tint(Color(0xFFDDEBF5)))))
    drawCircle(tint(Color(0xFFFFE08A)), radius = w * 0.07f, center = Offset(w * 0.8f, h * 0.18f))
    drawRect(tint(Color(0xFF6E9A4F)), topLeft = Offset(0f, h * 0.68f), size = Size(w, h * 0.32f))
    drawRect(tint(Color(0xFFD9C3A0)), topLeft = Offset(w * 0.3f, h * 0.42f), size = Size(w * 0.36f, h * 0.28f))
    drawPath(
        Path().apply {
            moveTo(w * 0.26f, h * 0.43f)
            lineTo(w * 0.48f, h * 0.27f)
            lineTo(w * 0.70f, h * 0.43f)
            close()
        },
        tint(Color(0xFF9C4A3C)),
    )
    drawRect(tint(Color(0xFF5B3A29)), topLeft = Offset(w * 0.45f, h * 0.56f), size = Size(w * 0.07f, h * 0.14f))
    val crown = if (then) 0.05f else 0.1f
    drawRect(tint(Color(0xFF6B4A2E)), topLeft = Offset(w * 0.14f, h * (0.68f - crown * 1.4f)), size = Size(w * 0.02f, h * crown * 1.4f))
    drawCircle(tint(Color(0xFF3F6E35)), radius = w * crown, center = Offset(w * 0.15f, h * (0.68f - crown * 1.6f)))
}

private fun sepia(c: Color): Color {
    val grey = 0.3f * c.red + 0.59f * c.green + 0.11f * c.blue
    return Color((grey * 1.07f).coerceAtMost(1f), grey * 0.95f, grey * 0.78f)
}
