package sh.gerra.again.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.gerra.again.domain.ReferencePhoto
import sh.gerra.again.platform.Photos
import sh.gerra.again.platform.PickResult
import sh.gerra.again.platform.loadOrNull
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.*
import sh.gerra.again.ui.ScreenModel
import sh.gerra.again.ui.components.AgainIcons
import sh.gerra.again.ui.components.Notice
import sh.gerra.again.ui.rememberScreenModel

internal data class HomeUiState(
    /** The old photo chosen last time, with its thumbnail, to carry on with it in one tap. */
    val last: ReferencePhoto? = null,
    val lastImage: ImageBitmap? = null,
    val picking: Boolean = false,
    val unreadable: Boolean = false,
)

internal class HomeModel(private val photos: Photos) : ScreenModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    /** Looks for the last photo again: the one shown may have been replaced since from the camera. */
    fun refresh() {
        scope.launch {
            val last = photos.lastReference()
            if (last == null) {
                _state.update { it.copy(last = null, lastImage = null) }
                return@launch
            }
            if (last == _state.value.last) return@launch
            val image = photos.loadOrNull(last.path, Photos.THUMBNAIL_PX)
            _state.update { it.copy(last = last.takeIf { image != null }, lastImage = image) }
        }
    }

    /** Opens the photo picker; [onPicked] gets the photo to recreate. Cancelling just stays here. */
    fun onChoose(onPicked: (ReferencePhoto) -> Unit) {
        if (_state.value.picking) return
        _state.update { it.copy(picking = true, unreadable = false) }
        photos.pickReference { result ->
            _state.update { it.copy(picking = false, unreadable = result == PickResult.Unreadable) }
            if (result is PickResult.Picked) onPicked(result.photo)
        }
    }
}

@Composable
internal fun HomeScreen(photos: Photos, onPicked: (ReferencePhoto) -> Unit) {
    val model = rememberScreenModel { HomeModel(photos) }
    val state by model.state.collectAsState()
    LaunchedEffect(Unit) { model.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Res.string.home_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(40.dp))

        val wide = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 64.dp)
        val last = state.last
        val lastImage = state.lastImage
        if (last != null && lastImage != null) {
            Image(
                bitmap = lastImage,
                contentDescription = stringResource(Res.string.home_last_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { onPicked(last) }, modifier = wide) {
                Text(stringResource(Res.string.home_continue), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = { model.onChoose(onPicked) }, enabled = !state.picking, modifier = wide) {
            Icon(AgainIcons.Photo, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.home_choose), style = MaterialTheme.typography.titleMedium)
        }
        if (state.unreadable) {
            Spacer(Modifier.height(16.dp))
            Notice(stringResource(Res.string.photo_unreadable), Modifier.widthIn(max = 420.dp))
        }

        Spacer(Modifier.height(40.dp))
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(1, Res.string.home_step_choose)
            Step(2, Res.string.home_step_align)
            Step(3, Res.string.home_step_take)
        }
    }
}

@Composable
private fun Step(number: Int, text: StringResource) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
