package com.geneing.epubreader.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geneing.epubreader.playback.MlKitVoiceModels
import com.geneing.epubreader.playback.SystemTtsVoice
import com.geneing.epubreader.playback.SystemTtsVoiceCatalog
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One picker for every narration voice the app can play: the device's Android
 * TTS voices (including network voices and the ML Kit GenAI `star` models) and
 * the Pocket TTS voices installed in the model pack.
 *
 * ML Kit voices that the platform reports as downloadable are fetched here and
 * the selection waits for the download to finish, so playback never starts on a
 * model that has not been installed yet.
 */
@Composable
internal fun PlaybackVoiceDialog(
    currentSystemVoice: String?,
    currentPocketVoice: String,
    pocketInstalled: Boolean,
    pocketVoices: List<String>,
    onDismiss: () -> Unit,
    onSystemVoiceSelected: (SystemTtsVoice) -> Unit,
    onPocketVoiceSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current
    val scope = rememberCoroutineScope()
    val mlKit = remember { MlKitVoiceModels() }
    var tab by remember { mutableStateOf(0) }
    var languageTag by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloadingVoice by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }

    DisposableEffectOnClose(mlKit::close)

    val voices by produceState<List<SystemTtsVoice>?>(initialValue = null) {
        value = withContext(Dispatchers.Main) {
            runCatching { SystemTtsVoiceCatalog.load(context) }.getOrElse { emptyList() }
        }
    }

    val languages = remember(voices) {
        voices.orEmpty()
            .groupBy { it.localeTag }
            .map { (tag, items) ->
                LanguageOption(
                    tag = tag,
                    label = items.first().localeLabel,
                    installed = items.any { it.installed },
                )
            }
            .sortedWith(compareByDescending<LanguageOption> { it.installed }.thenBy { it.label })
    }
    LaunchedEffect(languages) {
        if (languageTag == null || languages.none { it.tag == languageTag }) {
            val currentVoiceTag = currentSystemVoice?.let { id ->
                languages.firstOrNull { id.startsWith("${it.tag.lowercase()}-") }?.tag
            }
            languageTag = currentVoiceTag
                ?: languages.firstOrNull { it.tag.equals(locale.toLanguageTag(), true) }?.tag
                ?: languages.firstOrNull { it.tag.startsWith("en", ignoreCase = true) }?.tag
                ?: languages.firstOrNull()?.tag
        }
    }

    val selectedLanguage = languages.firstOrNull { it.tag == languageTag }
    val voicesInLanguage = remember(voices, languageTag) {
        voices.orEmpty().filter { it.localeTag == languageTag }
    }
    val mlKitStatuses by produceState<Map<String, Int>>(emptyMap(), languageTag) {
        value = languageTag?.let { tag ->
            withContext(Dispatchers.Main) {
                runCatching { mlKit.statuses(Locale.forLanguageTag(tag)) }.getOrDefault(emptyMap())
            }
        } ?: emptyMap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback voice") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Device") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Pocket") })
                }
                if (tab == 0) {
                    LanguageSelector(
                        selected = selectedLanguage,
                        options = languages,
                        onSelected = { languageTag = it.tag },
                    )
                    message?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (voices == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        } else if (voicesInLanguage.isEmpty()) {
                            Text(
                                "No voices were found for this language.",
                                modifier = Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            voicesInLanguage.forEach { voice ->
                                val status = voice.mlKitVoiceId?.let { mlKitStatuses[it] }
                                val busy = downloadingVoice == voice.id
                                SystemVoiceRow(
                                    voice = voice,
                                    selected = voice.id == currentSystemVoice,
                                    mlKitStatus = status,
                                    downloading = busy,
                                    downloadProgress = downloadProgress,
                                    onClick = {
                                        message = null
                                        if (voice.isMlKit && status == FeatureStatus.DOWNLOADABLE) {
                                            downloadingVoice = voice.id
                                            downloadProgress = 0f
                                            scope.launch {
                                                val locale = voice.locale
                                                val downloaded = withContext(Dispatchers.Main) {
                                                    runCatching {
                                                        mlKit.ensureDownloaded(
                                                            locale = locale,
                                                            voiceId = voice.mlKitVoiceId ?: voice.id,
                                                        ) { done, total ->
                                                            downloadProgress = if (total > 0) {
                                                                (done.toFloat() / total).coerceIn(0f, 1f)
                                                            } else {
                                                                0f
                                                            }
                                                        }
                                                    }.getOrDefault(false)
                                                }
                                                downloadingVoice = null
                                                if (downloaded) {
                                                    onSystemVoiceSelected(voice)
                                                } else {
                                                    message = "Couldn't download ${voice.displayName}. Check the connection and try again."
                                                }
                                            }
                                        } else {
                                            onSystemVoiceSelected(voice)
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    message?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (!pocketInstalled) {
                            Text(
                                "The Pocket TTS engine files are not provisioned on this device. Voices are listed from the model pack catalog; playback needs that pack installed in Settings.",
                                modifier = Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (pocketVoices.isEmpty()) {
                            Text(
                                "No Pocket voices were found in the model pack.",
                                modifier = Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            pocketVoices.forEach { voice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPocketVoiceSelected(voice) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = voice == currentPocketVoice,
                                        onClick = { onPocketVoiceSelected(voice) },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            voice.replaceFirstChar { it.titlecase(locale.platformLocale) },
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            "On-device · Pocket TTS",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    },
                ) { Text("Manage installed engine voices") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LanguageSelector(
    selected: LanguageOption?,
    options: List<LanguageOption>,
    onSelected: (LanguageOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = selected?.label ?: "Language",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option.installed) option.label else "${option.label} · not installed",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SystemVoiceRow(
    voice: SystemTtsVoice,
    selected: Boolean,
    mlKitStatus: Int?,
    downloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = !downloading, onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = !downloading)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        voice.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (voice.isMlKit) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (voice.isMlKit) {
                        Text(
                            "  ML Kit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    voice.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                mlKitStatus == FeatureStatus.DOWNLOADABLE -> Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Download voice",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (downloading) {
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private data class LanguageOption(
    val tag: String,
    val label: String,
    val installed: Boolean,
)

@Composable
private fun DisposableEffectOnClose(onClose: () -> Unit) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onClose() }
    }
}
