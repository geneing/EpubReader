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
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geneing.epubreader.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared narration bar shown above the library and inside the reader. It is the
 * single transport control for the active book; [onClick] opens the reader when
 * the bar is used from the library and is null in the reader itself.
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
    val coverBitmap by produceState<Bitmap?>(initialValue = null, playback.coverPath) {
        value = playback.coverPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
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
            }
        }
    }
}
