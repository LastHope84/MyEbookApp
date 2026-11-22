package com.example.myebookapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Display names never replace the original codes used to identify packs. */
internal fun translationLanguageName(code: String): String {
    val tag = code.replace('_', '-')
    val name = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)
    if (name.isBlank() || name.equals(tag, ignoreCase = true)) return code
    return name.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}

internal fun translationLanguageLabel(code: String): String {
    val name = translationLanguageName(code)
    return if (name.equals(code, ignoreCase = true)) code else "$name ($code)"
}

internal fun translationLanguagesMatch(codes: List<String>, query: String): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val searchable = codes.flatMap { listOf(it, translationLanguageName(it)) }
        .joinToString(" ").lowercase(Locale.ROOT)
    return terms.all { searchable.contains(it.lowercase(Locale.ROOT)) }
}

internal fun translationPairMatches(source: String, target: String, query: String): Boolean =
    translationLanguagesMatch(listOf(source, target), query)

/** This checks installation records, not the integrity of model files. */
internal fun translationRouteHasInstalledPacks(route: TranslationRoute): Boolean =
    route.packs.isNotEmpty() && route.packs.all { it.installedVersions.isNotEmpty() }

internal fun translationInstallationLabel(version: String): String =
    version.replace("(NotChecked)", "(not checked in this list)")

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
    var search by remember { mutableStateOf("") }
    var showAllPacks by remember { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }

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
        .sortedWith(compareBy({ translationLanguageName(it.source) }, { translationLanguageName(it.target) }))
    val matchingPacks = packs.filter { translationPairMatches(it.source, it.target, search) }
    val matchingRoutes = routes.filter { route ->
        val codes = listOf(route.source, route.target) + route.packs.flatMap { listOf(it.source, it.target) }
        translationRouteHasInstalledPacks(route) && translationLanguagesMatch(codes, search)
    }
    val visiblePacks = if (showAllPacks) matchingPacks else matchingPacks.take(6)
    Spacer(Modifier.height(12.dp))
    Text("Language packs", style = MaterialTheme.typography.titleSmall)
    Text("Packs remain installed when translation is switched off. This list does not recheck installed files; downloads are checked, and models are checked before use.", style = MaterialTheme.typography.bodySmall)
    if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (routes.isEmpty() && !busy) Text("No catalogue available.", style = MaterialTheme.typography.bodySmall)
    if (routes.isNotEmpty()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it; showAllPacks = false },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Search languages") },
            placeholder = { Text("Name or code, e.g. Italian or it") },
            singleLine = true
        )
        if (matchingPacks.isEmpty()) Text("No language packs match your search.", style = MaterialTheme.typography.bodySmall)
    }
    visiblePacks.forEach { pack ->
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("${translationLanguageLabel(pack.source)} → ${translationLanguageLabel(pack.target)}")
        Text("v${pack.version} • ${String.format("%.1f", pack.downloadBytes / 1048576.0)} MiB download", style = MaterialTheme.typography.bodySmall)
        Text(if (pack.installedVersions.isEmpty()) "Not installed" else "Installed: ${pack.installedVersions.joinToString { translationInstallationLabel(it) }}", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = enabled && !busy, onClick = { prompt = "download" to pack }) { Text("Download") }
            TextButton(enabled = !busy && pack.installedVersions.isNotEmpty(), onClick = {
                run { sharing = TranslationRuntime.sharedRoutes(context, pack.source, pack.target); prompt = "remove" to pack }
            }) { Text("Remove") }
        }
    }
    if (matchingPacks.size > 6) {
        TextButton(onClick = { showAllPacks = !showAllPacks }) {
            Text(if (showAllPacks) "Show fewer packs" else "Show all ${matchingPacks.size} matching packs")
        }
    }
    if (routes.isNotEmpty()) {
        TextButton(onClick = { showRoutes = !showRoutes }) {
            Text(if (showRoutes) "Hide installed routes" else "Show installed routes (${matchingRoutes.size})")
        }
        if (showRoutes) Text("Routes with installed pack records (via English when shown)", style = MaterialTheme.typography.titleSmall)
        if (showRoutes && matchingRoutes.isEmpty()) Text("No installed routes match your search.", style = MaterialTheme.typography.bodySmall)
        if (showRoutes) matchingRoutes.forEach { route ->
            val path = route.packs.joinToString(" + ") { "${translationLanguageLabel(it.source)} → ${translationLanguageLabel(it.target)}" }
            Text("${translationLanguageLabel(route.source)} → ${translationLanguageLabel(route.target)}: $path", style = MaterialTheme.typography.bodySmall)
        }
    }
    prompt?.let { (action, pack) ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(if (action == "download") "Download language pack?" else "Remove language pack?") },
            text = { Text(if (action == "download")
                "Download ${translationLanguageLabel(pack.source)} → ${translationLanguageLabel(pack.target)} (up to ${String.format("%.1f", pack.downloadBytes / 1048576.0)} MiB)? This uses the network."
                else "Remove all installed versions of ${translationLanguageLabel(pack.source)} → ${translationLanguageLabel(pack.target)}?" +
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
