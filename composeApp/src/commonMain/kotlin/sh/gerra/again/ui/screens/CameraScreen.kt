package sh.gerra.again.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.Camera
import sh.gerra.again.platform.CameraPermission
import sh.gerra.again.platform.Photos
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.*
import sh.gerra.again.ui.components.ActionButton
import sh.gerra.again.ui.components.AgainIcons
import sh.gerra.again.ui.components.FramedLayout
import sh.gerra.again.ui.components.GuideImage
import sh.gerra.again.ui.components.Notice
import sh.gerra.again.ui.rememberScreenModel
import sh.gerra.again.ui.theme.Scrim

/**
 * The live camera in a frame shaped like the old photo, with the old photo laid over it, see-through,
 * to be pinched and dragged into line. The frame is also exactly what the shutter records: the camera
 * crops its photograph to it, so the new photo has the old one's proportions.
 */
@Composable
internal fun CameraScreen(
    reference: ReferencePhoto,
    camera: Camera,
    photos: Photos,
    onChangedPhoto: (ReferencePhoto) -> Unit,
    onCaptured: (Guide, CapturedPhoto) -> Unit,
) {
    val model = rememberScreenModel { CameraModel(reference, photos) }
    val state by model.state.collectAsState()
    val permission by camera.permission.collectAsState()
    LaunchedEffect(Unit) { if (camera.permission.value == CameraPermission.NotAsked) camera.requestPermission() }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        FramedLayout(
            modifier = Modifier.background(Color.Black),
            top = { column ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = if (column) Arrangement.SpaceEvenly else Arrangement.SpaceBetween,
                ) {
                    val button = if (column) Modifier.weight(1f) else Modifier.widthIn(min = 88.dp)
                    ActionButton(AgainIcons.Photo, stringResource(Res.string.camera_change_photo), { model.onChangePhoto(onChangedPhoto) }, button)
                    if (state.hasFlash) {
                        ActionButton(
                            icon = if (state.flash) AgainIcons.FlashOn else AgainIcons.FlashOff,
                            label = stringResource(if (state.flash) Res.string.camera_flash_on else Res.string.camera_flash_off),
                            onClick = model::onFlashToggled,
                            modifier = button,
                        )
                    }
                    ActionButton(Icons.Filled.Refresh, stringResource(Res.string.camera_reset), model::onResetAlignment, button, enabled = state.guide.isMoved)
                }
            },
            bottom = { column ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (column) 0.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OpacityControl(state.guide.opacity, model::onOpacityChanged)
                    Spacer(Modifier.height(if (column) 8.dp else 12.dp))
                    Shutter(enabled = state.canCapture, capturing = state.isCapturing, onClick = { model.onCapture(onCaptured) })
                }
            },
        ) {
            val image = state.reference
            when {
                state.problem == CameraProblem.ReferenceUnreadable -> Notice(stringResource(Res.string.photo_unreadable), Modifier.padding(24.dp))
                image == null -> CircularProgressIndicator()
                permission != CameraPermission.Granted -> PermissionRequest(permission, camera)
                else -> Viewfinder(camera, model, state, image)
            }
            val message = when (state.problem) {
                CameraProblem.CaptureFailed -> Res.string.camera_capture_failed
                CameraProblem.PickedUnreadable -> Res.string.photo_unreadable
                else -> null
            }
            if (message != null) Notice(stringResource(message), Modifier.align(Alignment.TopCenter).padding(16.dp))
        }
    }
}

@Composable
private fun Viewfinder(camera: Camera, model: CameraModel, state: CameraUiState, image: ImageBitmap) {
    val frameDescription = stringResource(Res.string.camera_frame_description)
    // Shaped like the old photo and as large as fits.
    Box(Modifier.aspectRatio(image.width.toFloat() / image.height).clipToBounds().background(Color.Black)) {
        camera.Preview(Modifier.fillMaxSize(), model::onCameraStatus)
        GuideImage(image, state.guide.transform, Modifier.fillMaxSize(), opacity = state.guide.opacity)
        CaptureFlash(state.shots)
        // On top of everything, so the gestures reach the guide and never the platform's preview view.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = frameDescription }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        model.onTransformChanged(pan.x / size.width, pan.y / size.height, zoom, rotation)
                    }
                },
        )
        if (state.problem == CameraProblem.CameraUnavailable) {
            Notice(stringResource(Res.string.camera_unavailable), Modifier.align(Alignment.Center).padding(24.dp))
        } else if (state.cameraReady && !state.guide.isMoved) {
            Text(
                stringResource(Res.string.camera_hint),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Scrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A quick white blink over the frame each time the shutter is pressed. Keyed on the count of
 * presses rather than on the capture itself, so a capture that ends at once still blinks to the end.
 */
@Composable
private fun CaptureFlash(shots: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots > 0) {
            alpha.snapTo(0.7f)
            alpha.animateTo(0f, tween(300))
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}

@Composable
private fun OpacityControl(opacity: Float, onChange: (Float) -> Unit) {
    val percent = stringResource(Res.string.percent, (opacity * 100).roundToInt())
    val description = stringResource(Res.string.camera_opacity_description)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.camera_opacity), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = opacity,
            onValueChange = onChange,
            valueRange = Guide.MIN_OPACITY..Guide.MAX_OPACITY,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = percent
                },
        )
        Text(percent, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
    }
}

/** The one big button: a white ring and disc, with a spinner in it while the photo is taken. */
@Composable
private fun Shutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    val description = stringResource(Res.string.camera_shutter)
    val tint = if (enabled || capturing) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .border(4.dp, tint, CircleShape)
            .padding(9.dp)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/** Why the camera is needed, and the way to allow it: again, or in Settings once it has been refused. */
@Composable
private fun PermissionRequest(permission: CameraPermission, camera: Camera) {
    Column(
        Modifier.padding(32.dp).widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(AgainIcons.Camera, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.camera_permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(Res.string.camera_permission_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (permission == CameraPermission.Denied) {
            Text(
                stringResource(Res.string.camera_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = camera::requestPermission, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(Res.string.camera_permission_allow), style = MaterialTheme.typography.titleMedium)
        }
        if (permission == CameraPermission.Denied) {
            OutlinedButton(onClick = camera::openSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(Res.string.camera_permission_settings), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}
