package sh.gerra.again.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.gerra.again.domain.OverlayTransform

/**
 * The old photo placed by [transform] in a frame of its own proportions: fitted to the frame, then
 * scaled and turned about its centre and moved by fractions of the frame's size. The camera screen
 * draws it see-through over the viewfinder and the comparison draws it solid, so both show it in
 * the same place.
 */
@Composable
internal fun GuideImage(image: ImageBitmap, transform: OverlayTransform, modifier: Modifier = Modifier, opacity: Float = 1f) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.offsetX * size.width
            translationY = transform.offsetY * size.height
            rotationZ = transform.rotationDegrees
            alpha = opacity
        },
    )
}

/**
 * A photo's frame and the controls around it: the controls above and below it when the phone is
 * upright, beside it when it is turned. The frame gets every bit of space the controls leave; [top]
 * and [bottom] are told whether they are stacked in a narrow column.
 */
@Composable
internal fun FramedLayout(
    top: @Composable (column: Boolean) -> Unit,
    bottom: @Composable (column: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    frame: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(8.dp), contentAlignment = Alignment.Center, content = frame)
                Column(
                    Modifier.width(264.dp).fillMaxHeight().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    top(true)
                    bottom(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                top(false)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center, content = frame)
                bottom(false)
            }
        }
    }
}

/** An icon with its label under it: every control says what it does in words. */
@Composable
internal fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A short message about something that went wrong or right, read out by screen readers as it appears. */
@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier, isError: Boolean = true) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // An icon as well as the colour, so an error never depends on seeing red.
            if (isError) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
