package sh.gerra.again.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Decodes an encoded photo (JPEG, PNG, WebP, HEIF where the platform has it) the way it is meant to
 * be seen: turned by its EXIF orientation, and no larger than [maxPx] on its long side. Throws when
 * the bytes are not a picture. The desktop and iOS both draw with Skia, so both decode with this.
 */
internal fun decodeUpright(bytes: ByteArray, maxPx: Int): ImageBitmap {
    val origin = Codec.makeFromData(Data.makeFromBytes(bytes)).encodedOrigin
    val image = Image.makeFromEncoded(bytes)
    // Clockwise quarter turns that stand the stored pixels upright. Mirrored origins are rare
    // enough (front cameras of some phones) to be shown as stored.
    val turns = when (origin) {
        EncodedOrigin.RIGHT_TOP -> 1
        EncodedOrigin.BOTTOM_RIGHT -> 2
        EncodedOrigin.LEFT_BOTTOM -> 3
        else -> 0
    }
    val width = if (turns % 2 == 1) image.height else image.width
    val height = if (turns % 2 == 1) image.width else image.height
    val scale = min(1f, maxPx.toFloat() / max(width, height))
    val surface = Surface.makeRasterN32Premul((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    surface.canvas.apply {
        scale(scale, scale)
        when (turns) {
            1 -> translate(width.toFloat(), 0f).rotate(90f)
            2 -> translate(width.toFloat(), height.toFloat()).rotate(180f)
            3 -> translate(0f, height.toFloat()).rotate(270f)
        }
        drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
    }
    return surface.makeImageSnapshot().toComposeImageBitmap()
}
