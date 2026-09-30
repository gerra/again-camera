package sh.gerra.again.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    // Skia applies the EXIF orientation itself (mirrored ones too): the image it hands back is
    // already upright, with its width and height those of the photo as seen. Turning it again here
    // would lay a phone's portrait photo on its side.
    val image = Image.makeFromEncoded(bytes)
    val scale = min(1f, maxPx.toFloat() / max(image.width, image.height))
    val surface = Surface.makeRasterN32Premul((image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1))
    surface.canvas.apply {
        scale(scale, scale)
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
