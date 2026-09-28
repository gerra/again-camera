package sh.gerra.again.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import sh.gerra.again.domain.OverlayTransform
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.*
import sh.gerra.again.ui.theme.Scrim

/**
 * Then and now in one frame of the new photo's proportions: the new photo fills it, and the old one,
 * placed as it was lined up in the viewfinder, covers it up to the divider. Dragging anywhere in the
 * frame moves the divider; a tap puts it where the finger is. For screen readers the divider is an
 * adjustable value from all new (0) to all old (1).
 */
@Composable
internal fun BeforeAfter(
    old: ImageBitmap,
    transform: OverlayTransform,
    new: ImageBitmap,
    divider: Float,
    onDividerSet: (Float) -> Unit,
    onDividerDragged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.compare_divider)
    val state = stringResource(Res.string.compare_divider_state, (divider * 100).roundToInt())
    BoxWithConstraints(
        modifier
            .aspectRatio(new.width.toFloat() / new.height)
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onDividerSet(it.x / size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> onDividerDragged(dx / size.width) } }
            .semantics {
                contentDescription = description
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(divider, 0f..1f)
                setProgress { onDividerSet(it); true }
            },
    ) {
        Image(new, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(right = size.width * divider) { this@drawWithContent.drawContent() } }
                .background(Color.Black),
        ) {
            GuideImage(old, transform, Modifier.fillMaxSize())
        }

        Label(stringResource(Res.string.compare_then), Modifier.align(Alignment.TopStart))
        Label(stringResource(Res.string.compare_now), Modifier.align(Alignment.TopEnd))

        // The divider: a thin line with a round handle, centred on the split.
        val x = maxWidth * divider
        Box(Modifier.fillMaxHeight().width(2.dp).offset(x = x - 1.dp).background(Color.White))
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x - 24.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(50))
            .background(Scrim)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
