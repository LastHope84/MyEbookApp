package com.example.myebookapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pack metadata is local; explicit confirmation is required before any network or deletion. */
@Composable
internal fun TranslationPackSettings(enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var routes by remember { mutableStateOf<List<TranslationRoute>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Pair<String, TranslationPack>?>(null) }
    var sharing by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun refresh() { routes = TranslationRuntime.routes(context) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Operation failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Could not load local catalogue" }
        finally { busy = false }
    }
    val packs = routes.flatMap { it.packs }.distinctBy { it.source to it.target }
        .sortedWith(compareBy({ it.source }, { it.target }))
    Spacer(Modifier.height(12.dp))
    Text("Language packs", style = MaterialTheme.typography.titleSmall)
    Text("Packs remain installed when translation is switched off. Status is installation metadata; models are checked before use.", style = MaterialTheme.typography.bodySmall)
    if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (routes.isEmpty() && !busy) Text("No catalogue available.", style = MaterialTheme.typography.bodySmall)
    packs.forEach { pack ->
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("${pack.source} → ${pack.target} • v${pack.version} • ${String.format("%.1f", pack.downloadBytes / 1048576.0)} MiB download")
        Text(if (pack.installedVersions.isEmpty()) "Not installed" else "Installed: ${pack.installedVersions.joinToString()}", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = enabled && !busy, onClick = { prompt = "download" to pack }) { Text("Download") }
            TextButton(enabled = !busy && pack.installedVersions.isNotEmpty(), onClick = {
                run { sharing = TranslationRuntime.sharedRoutes(context, pack.source, pack.target); prompt = "remove" to pack }
            }) { Text("Remove") }
        }
    }
    if (routes.isNotEmpty()) {
        Text("Supported routes (via English when shown)", style = MaterialTheme.typography.titleSmall)
        routes.forEach { route ->
            val path = route.packs.joinToString(" + ") { "${it.source}→${it.target}" }
            val status = if (route.packs.all { it.installedVersions.isNotEmpty() }) "installed (not yet verified)" else "missing pack(s)"
            Text("${route.source} → ${route.target}: $path • $status", style = MaterialTheme.typography.bodySmall)
        }
    }
    prompt?.let { (action, pack) ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(if (action == "download") "Download language pack?" else "Remove language pack?") },
            text = { Text(if (action == "download")
                "Download ${pack.source} → ${pack.target} (up to ${String.format("%.1f", pack.downloadBytes / 1048576.0)} MiB)? This uses the network."
                else "Remove all installed versions of ${pack.source} → ${pack.target}?" +
                    if (sharing.isEmpty()) "" else " These routes may also stop working: ${sharing.joinToString()}.") },
            confirmButton = { TextButton(onClick = {
                prompt = null
                run {
                    try {
                        if (action == "download") {
                            TranslationRuntime.downloadPack(context, pack.source, pack.target)
                            message = "Download complete."
                        } else message = TranslationRuntime.removePack(context, pack.source, pack.target)
                    } finally { refresh() }
                }
            }) { Text(if (action == "download") "Download" else "Remove") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } }
        )
    }
}
