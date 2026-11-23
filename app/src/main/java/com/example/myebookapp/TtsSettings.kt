package com.example.myebookapp

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

object TtsPrefs {
    private const val PREFS = "tts_prefs"
    private const val KEY_VOICE = "voice_name"
    private const val KEY_LOCALE = "locale_tag"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun voiceName(context: Context): String? = prefs(context).getString(KEY_VOICE, null)

    fun localeTag(context: Context): String? = prefs(context).getString(KEY_LOCALE, null)

    fun save(context: Context, voiceName: String?, localeTag: String?) {
        prefs(context).edit()
            .putString(KEY_VOICE, voiceName)
            .putString(KEY_LOCALE, localeTag)
            .apply()
    }

    fun summary(context: Context): String {
        val tag = localeTag(context) ?: return "System default"
        val locale = Locale.forLanguageTag(tag)
        val name = voiceName(context) ?: return locale.displayName
        return "${locale.displayName} • $name"
    }

    /** Returns false when the saved language is not installed on this phone. */
    fun apply(engine: TextToSpeech?, context: Context): Boolean {
        engine ?: return false
        val tag = localeTag(context) ?: return true
        val locale = Locale.forLanguageTag(tag)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val name = voiceName(context)
        if (name != null) {
            engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it }
        }
        return true
    }
}

@Composable
fun TtsSettingsSection() {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(TtsPrefs.summary(context)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Voice and language", style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { showPicker = true }) { Text("Choose") }
    }

    if (showPicker) {
        TtsVoicePickerDialog(onDismiss = {
            showPicker = false
            summary = TtsPrefs.summary(context)
        })
    }
}

@Composable
private fun TtsVoicePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val engineRef = remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            val instance = engineRef.value
            if (status == TextToSpeech.SUCCESS && instance != null) {
                voices = instance.voices
                    ?.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                    .orEmpty()
            } else if (status != TextToSpeech.SUCCESS) {
                error = "Text-to-speech is not available on this phone."
            }
        }
        engineRef.value = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            engineRef.value = null
        }
    }

    val filtered = voices.filter { voice ->
        query.isBlank() ||
            voice.locale.displayName.contains(query, ignoreCase = true) ||
            voice.locale.toLanguageTag().contains(query, ignoreCase = true) ||
            voice.name.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read aloud voice") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search languages or voices") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    TtsPrefs.save(context, null, null)
                    onDismiss()
                }) { Text("Use system default") }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.name }) { voice ->
                        TextButton(
                            onClick = {
                                TtsPrefs.save(context, voice.name, voice.locale.toLanguageTag())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(voice.locale.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    voice.name + if (voice.isNetworkConnectionRequired) " • needs internet" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
