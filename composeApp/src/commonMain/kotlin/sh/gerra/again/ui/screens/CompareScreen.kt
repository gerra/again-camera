package sh.gerra.again.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import sh.gerra.again.comparison.ComparisonState
import sh.gerra.again.domain.CapturedPhoto
import sh.gerra.again.domain.Guide
import sh.gerra.again.platform.Photos
import sh.gerra.again.platform.loadOrNull
import sh.gerra.again.resources.Res
import sh.gerra.again.resources.*
import sh.gerra.again.ui.ScreenModel
import sh.gerra.again.ui.components.AgainIcons
import sh.gerra.again.ui.components.BeforeAfter
import sh.gerra.again.ui.components.FramedLayout
import sh.gerra.again.ui.components.Notice
import sh.gerra.again.ui.rememberScreenModel

internal enum class CompareMessage { Saved, SaveFailed, ShareFailed }

internal data class CompareUiState(
    val comparison: ComparisonState,
    val old: ImageBitmap? = null,
    val new: ImageBitmap? = null,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val message: CompareMessage? = null,
)

internal class CompareModel(guide: Guide, capture: CapturedPhoto, private val photos: Photos) : ScreenModel() {
    private val _state = MutableStateFlow(CompareUiState(ComparisonState(guide, capture)))
    val state: StateFlow<CompareUiState> = _state

    init {
        scope.launch {
            val old = async { photos.loadOrNull(guide.reference.path) }
            val new = async { photos.loadOrNull(capture.path) }
            val (oldImage, newImage) = old.await() to new.await()
            _state.update { it.copy(old = oldImage, new = newImage, loadFailed = oldImage == null || newImage == null) }
        }
    }

    fun onDividerSet(fraction: Float) = _state.update { it.copy(comparison = it.comparison.withDivider(fraction)) }

    fun onDividerDragged(fraction: Float) = _state.update { it.copy(comparison = it.comparison.draggedBy(fraction)) }

    /** Copies the new photo, as the camera took it, into the gallery. Once is enough. */
    fun onSave() {
        val current = _state.value
        if (current.saving || current.saved) return
        _state.update { it.copy(saving = true, message = null) }
        scope.launch {
            val saved = try {
                photos.save(current.comparison.capture)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(saving = false, saved = saved, message = if (saved) CompareMessage.Saved else CompareMessage.SaveFailed) }
        }
    }

    fun onShare() {
        val shared = try {
            photos.share(_state.value.comparison.capture)
            true
        } catch (e: Exception) {
            false
        }
        _state.update { it.copy(message = if (shared) it.message.takeIf { m -> m == CompareMessage.Saved } else CompareMessage.ShareFailed) }
    }
}

/** Then and now, split by a divider to drag, with the new photo's Save and Share, and Retake. */
@Composable
internal fun CompareScreen(guide: Guide, capture: CapturedPhoto, photos: Photos, onRetake: () -> Unit) {
    val model = rememberScreenModel { CompareModel(guide, capture, photos) }
    val state by model.state.collectAsState()

    FramedLayout(
        top = {
            Text(
                stringResource(Res.string.compare_hint),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        },
        bottom = { column ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.message) {
                    CompareMessage.Saved -> Notice(stringResource(Res.string.compare_saved_message), isError = false)
                    CompareMessage.SaveFailed -> Notice(stringResource(Res.string.compare_save_failed))
                    CompareMessage.ShareFailed -> Notice(stringResource(Res.string.compare_share_failed))
                    null -> Unit
                }
                val canUse = state.new != null
                val retake: @Composable (Modifier) -> Unit = { m ->
                    OutlinedButton(onClick = onRetake, modifier = m) { Label(Icons.Filled.Refresh, stringResource(Res.string.compare_retake)) }
                }
                val share: @Composable (Modifier) -> Unit = { m ->
                    FilledTonalButton(onClick = model::onShare, enabled = canUse, modifier = m) { Label(Icons.Filled.Share, stringResource(Res.string.compare_share)) }
                }
                val save: @Composable (Modifier) -> Unit = { m ->
                    Button(onClick = model::onSave, enabled = canUse && !state.saving && !state.saved, modifier = m) {
                        when {
                            state.saving -> CircularProgressIndicator(Modifier.padding(end = 8.dp).width(20.dp), strokeWidth = 2.dp)
                            // The word changes as well as the icon, so being saved never rests on colour.
                            state.saved -> Label(Icons.Filled.Check, stringResource(Res.string.compare_saved))
                            else -> Label(AgainIcons.Save, stringResource(Res.string.compare_save))
                        }
                    }
                }
                val tall = Modifier.heightIn(min = 56.dp)
                if (column) {
                    save(tall.fillMaxWidth())
                    share(tall.fillMaxWidth())
                    retake(tall.fillMaxWidth())
                } else {
                    // Save, the one to press, across the whole width; the other two share the row under it.
                    save(tall.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        retake(tall.weight(1f))
                        share(tall.weight(1f))
                    }
                }
            }
        },
    ) {
        val old = state.old
        val new = state.new
        when {
            old != null && new != null -> BeforeAfter(
                old = old,
                transform = state.comparison.guide.transform,
                new = new,
                divider = state.comparison.divider,
                onDividerSet = model::onDividerSet,
                onDividerDragged = model::onDividerDragged,
            )
            state.loadFailed -> Notice(stringResource(Res.string.compare_load_failed), Modifier.padding(24.dp))
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun RowScope.Label(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
}
