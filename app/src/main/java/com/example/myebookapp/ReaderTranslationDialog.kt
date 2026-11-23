package com.example.myebookapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Reader-only UI. TranslationRuntime must use installed models only, never download. */
internal class ReaderTranslationDialog(private val activity: FragmentActivity) : DefaultLifecycleObserver {
    private sealed interface Phase {
        data object Choose : Phase
        data object Progress : Phase
        data class Message(val title: String, val body: String) : Phase
    }

    private var dialog: Dialog? = null
    private var job: Job? = null
    private var phase by mutableStateOf<Phase>(Phase.Choose)
    private var source by mutableStateOf("")
    private var target by mutableStateOf("")
    private var sourceError by mutableStateOf<String?>(null)
    private var targetError by mutableStateOf<String?>(null)

    fun show(text: String) {
        activity.lifecycle.addObserver(this)
        if (text.length > 12000) {
            present(Phase.Message("Selection too long", "Please select a shorter passage (up to 12,000 characters)."))
            return
        }
        present(Phase.Choose, text)
    }

    private fun present(initial: Phase, text: String = "") {
        phase = initial
        val host = Dialog(activity)
        val view = ComposeView(activity).apply {
            setContent {
                MyEbookTheme(mode = ThemePrefs.getMode(activity)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        when (val current = phase) {
                            is Phase.Choose -> ChooseContent(text)
                            is Phase.Progress -> ProgressContent()
                            is Phase.Message -> MessageContent(current)
                        }
                    }
                }
            }
        }
        host.setContentView(view)
        host.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        host.setOnDismissListener { if (dialog === host) close() }
        host.show()
        val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
        host.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog = host
    }

    @Composable
    private fun ChooseContent(text: String) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Translate offline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Choose both language codes. Only installed language packs are used; nothing is downloaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            val sourceSupport: (@Composable () -> Unit)? = if (sourceError != null) {
                { Text(sourceError.orEmpty()) }
            } else null
            OutlinedTextField(
                value = source,
                onValueChange = { source = it; sourceError = null },
                label = { Text("Source language") },
                placeholder = { Text("en") },
                singleLine = true,
                isError = sourceError != null,
                supportingText = sourceSupport,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val targetSupport: (@Composable () -> Unit)? = if (targetError != null) {
                { Text(targetError.orEmpty()) }
            } else null
            OutlinedTextField(
                value = target,
                onValueChange = { target = it; targetError = null },
                label = { Text("Target language") },
                placeholder = { Text("it") },
                singleLine = true,
                isError = targetError != null,
                supportingText = targetSupport,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { close() }) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { startTranslation(text) }) { Text("Translate") }
            }
        }
    }

    @Composable
    private fun ProgressContent() {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Translating offline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Using installed language packs…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { close() }) { Text("Cancel") }
        }
    }

    @Composable
    private fun MessageContent(message: Phase.Message) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(message.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                message.body,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { close() }) { Text("Close") }
            }
        }
    }

    private fun startTranslation(text: String) {
        val from = source.trim().lowercase(Locale.ROOT)
        val to = target.trim().lowercase(Locale.ROOT)
        val code = Regex("[a-z]{2,3}")
        if (!code.matches(from)) {
            sourceError = "Enter a language code, for example en"
            return
        }
        if (!code.matches(to) || from == to) {
            targetError = "Enter a different language code, for example it"
            return
        }
        if (!ThemePrefs.isOfflineTranslationEnabled(activity) || !TranslationRuntime.isAvailable()) {
            phase = Phase.Message("Translation unavailable", "Enable offline translation in settings first.")
            return
        }
        phase = Phase.Progress
        job = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TranslationRuntime.translate(activity.applicationContext, from, to, text)
                }
                phase = Phase.Message("Translation", result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val missing = generateSequence(error as Throwable?) { it.cause }
                    .take(20).any { it.javaClass.simpleName == "ModelNotInstalledException" }
                val detail = if (missing) {
                    "The required language pack is not installed. Install it in settings before trying again."
                } else {
                    "Translation failed. The language pair may be unsupported or its required pack may be missing. Check the installed packs and try again."
                }
                phase = Phase.Message("Unable to translate offline", detail)
            }
        }
    }

    private fun close() {
        job?.cancel()
        job = null
        val current = dialog
        dialog = null
        current?.setOnDismissListener(null)
        current?.dismiss()
        activity.lifecycle.removeObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }
}
