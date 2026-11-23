package com.example.myebookapp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsBottomSheet(
    themeMode: AppThemeMode,
    accent: AppAccent,
    amoledDark: Boolean,
    autoOpenLastBook: Boolean,
    kindleEmail: String,
    convertedOutputUri: String?,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onAccentChange: (AppAccent) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onAutoOpenChange: (Boolean) -> Unit,
    onKindleEmailSave: (String) -> Unit,
    onPickConvertedFolder: () -> Unit,
    onResetConvertedFolder: () -> Unit,
    onCleanDuplicates: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var emailDraft by remember(kindleEmail) { mutableStateOf(kindleEmail) }
    var storageSummary by remember { mutableStateOf("Calculating storage...") }
    var cacheClearedMsg by remember { mutableStateOf<String?>(null) }
    var translationEnabled by remember { mutableStateOf(ThemePrefs.isOfflineTranslationEnabled(context)) }

    LaunchedEffect(Unit) {
        storageSummary = withContext(Dispatchers.IO) { computeStorageSummary(context) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel("Appearance & Material You")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = themeMode == m,
                        onClick = { onThemeModeChange(m) },
                        label = { Text(m.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppAccent.entries.forEach { acc ->
                    val isSelected = accent == acc
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onAccentChange(acc) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(acc.swatchColor)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onBackground else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(acc.label.substringBefore(" "), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AMOLED Pitch-Black Dark UI", style = MaterialTheme.typography.bodyLarge)
                    Text("Pure black backgrounds in Dark Mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = amoledDark, onCheckedChange = onAmoledChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel("E-Reader Behavior")

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reopen Last Book on Launch", style = MaterialTheme.typography.bodyLarge)
                    Text("Wake straight into your current book like a Kindle", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoOpenLastBook, onCheckedChange = onAutoOpenChange)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel("Offline Translation")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable offline translation", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (TranslationAvailability.isAvailable)
                            "Experimental, on-device. Models download only when requested."
                        else "Not available yet in this build.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = translationEnabled,
                    onCheckedChange = { enabled ->
                        ThemePrefs.saveOfflineTranslationEnabled(context, enabled)
                        translationEnabled = ThemePrefs.isOfflineTranslationEnabled(context)
                    },
                    enabled = TranslationAvailability.isAvailable
                )
            }

            if (TranslationAvailability.isAvailable) TranslationPackSettings(enabled = translationEnabled)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel("Converted EPUBs Output Folder (SD Card)")

            Text(
                text = convertedOutputUri?.let { FileOpener.formatReadablePath(it) }
                    ?: "App Storage (default)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickConvertedFolder, modifier = Modifier.weight(1f)) {
                    Text("Pick SD / Folder")
                }
                if (convertedOutputUri != null) {
                    TextButton(onClick = onResetConvertedFolder) { Text("Reset") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel("Send to Kindle Email")

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = emailDraft,
                    onValueChange = { emailDraft = it },
                    placeholder = { Text("yourname@kindle.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onKindleEmailSave(emailDraft) }) { Text("Save") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SectionLabel("Library Maintenance")

            Text(storageSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCleanDuplicates, modifier = Modifier.weight(1f)) {
                    Text("Clean Duplicates")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            clearTempCache(context)
                            val updated = computeStorageSummary(context)
                            withContext(Dispatchers.Main) {
                                storageSummary = updated
                                cacheClearedMsg = "Temp cache cleared!"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Temp Cache")
                }
            }

            cacheClearedMsg?.let { msg ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            Text(
                "Version $versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

private fun dirSizeBytes(dir: File): Long {
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format("%.1f MB", mb)
}

private fun computeStorageSummary(context: Context): String {
    val coversMb = formatMb(dirSizeBytes(File(context.filesDir, "covers")))
    val convertedMb = formatMb(dirSizeBytes(File(context.filesDir, "converted_epubs")))
    val cacheMb = formatMb(dirSizeBytes(context.cacheDir))
    return "Covers: $coversMb • Converted EPUBs: $convertedMb • Temp cache: $cacheMb"
}

private fun clearTempCache(context: Context) {
    context.cacheDir.listFiles()?.forEach { child ->
        if (child.name == "shared_books" ||
            child.name.startsWith("mobi_work_") ||
            child.name.startsWith("epub_edit_") ||
            child.name == "current_book.epub"
        ) {
            runCatching { child.deleteRecursively() }
        }
    }
}
