package com.geneing.epubreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.SpeechEngine
import com.geneing.epubreader.playback.PlaybackUiState
import com.geneing.epubreader.playback.PocketModelUiState
import com.geneing.epubreader.playback.PocketTtsModelManager
import com.geneing.epubreader.playback.persistPlaybackVoice
import com.geneing.epubreader.playback.persistSpeechRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Shared narration bar shown above the library and inside the reader. It is the
 * single transport control for the active book; [onClick] opens the reader when
 * the bar is used from the library and is null in the reader itself.
 *
 * The gear button opens the voice picker. Changing a voice persists it and, when
 * narration is running, asks the playback service to restart the current
 * sentence with the new voice without leaving the page.
 */
@Composable
internal fun PlaybackMiniPlayer(
    playback: PlaybackUiState,
    onSkipBack: () -> Unit,
    onToggle: () -> Unit,
    onSkipForward: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coverBitmap by produceState<Bitmap?>(initialValue = null, playback.coverPath) {
        value = playback.coverPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        }
    }
    var voicePickerOpen by remember { mutableStateOf(false) }
    var rateDialogOpen by remember { mutableStateOf(false) }
    var speechRate by remember(rateDialogOpen) {
        mutableStateOf(AppPreferences.speechRate(context))
    }
    // The picker reads the persisted selection each time it opens, so a voice
    // chosen for another book is reflected immediately.
    var systemVoice by remember(voicePickerOpen) {
        mutableStateOf(AppPreferences.systemTtsVoice(context))
    }
    var pocketVoice by remember(voicePickerOpen) {
        mutableStateOf(AppPreferences.pocketTtsVoice(context))
    }
    val pocketState by produceState<PocketModelUiState?>(null, voicePickerOpen) {
        value = if (voicePickerOpen) {
            withContext(Dispatchers.IO) {
                runCatching { PocketTtsModelManager(context).status() }.getOrNull()
            } ?: PocketModelUiState()
        } else {
            null
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap!!.asImageBitmap(),
                        contentDescription = "Cover of ${playback.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.size(48.dp).padding(10.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(playback.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${playback.author?.takeIf(String::isNotBlank)?.let { "$it · " } ?: ""}${(playback.progress * 100).toInt()}% read",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Close, contentDescription = "Stop playback and close mini-player")
                }
            }
            LinearProgressIndicator(
                progress = { playback.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSkipBack) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous sentence")
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playback.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause playback" else "Resume playback",
                    )
                }
                IconButton(onClick = onSkipForward) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Next sentence")
                }
                TextButton(
                    onClick = { rateDialogOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        "${"%.1f".format(speechRate)}×",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { voicePickerOpen = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Select playback voice")
                }
            }
        }
    }

    if (voicePickerOpen && pocketState != null) {        val modelState = pocketState!!
        PlaybackVoiceDialog(
            currentSystemVoice = systemVoice,
            currentPocketVoice = pocketVoice,
            pocketInstalled = modelState.installed,
            pocketVoices = modelState.voices,
            onDismiss = { voicePickerOpen = false },
            onSystemVoiceSelected = { voice ->
                systemVoice = voice.id
                voicePickerOpen = false
                persistPlaybackVoice(context, SpeechEngine.ANDROID_SYSTEM, voice.id)
            },
            onPocketVoiceSelected = { voice ->
                pocketVoice = voice
                voicePickerOpen = false
                persistPlaybackVoice(context, SpeechEngine.POCKET, voice)
            },
        )
    }

    if (rateDialogOpen) {
        AlertDialog(
            onDismissRequest = { rateDialogOpen = false },
            title = { Text("Speech rate") },
            text = {
                Column {
                    Text(
                        "${"%.2f".format(speechRate)}×",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = speechRate,
                        onValueChange = { speechRate = (it * 10f).roundToInt() / 10f },
                        onValueChangeFinished = { persistSpeechRate(context, speechRate) },
                        valueRange = AppPreferences.MIN_SPEECH_RATE..AppPreferences.MAX_SPEECH_RATE,
                        steps = 14,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Slower", style = MaterialTheme.typography.labelSmall)
                        Text("Faster", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistSpeechRate(context, speechRate)
                        rateDialogOpen = false
                    },
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { rateDialogOpen = false }) { Text("Cancel") } },
        )
    }
}
