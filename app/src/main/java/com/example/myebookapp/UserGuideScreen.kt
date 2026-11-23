package com.example.myebookapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class GuideTopic(val id: String, val title: String, val summary: String, val sections: List<Pair<String, String>>)

private fun GuideTopic.searchText(): String = buildString {
    append(title).append(' ').append(summary).append(' ')
    sections.forEach { append(it.first).append(' ').append(it.second).append(' ') }
}.lowercase()

private fun GuideTopic.matches(query: String): Boolean {
    val haystack = searchText()
    return query.split(Regex("\\s+")).filter { it.isNotEmpty() }.all { haystack.contains(it) }
}

private fun GuideTopic.matchingSections(query: String): List<String> =
    sections.filter { (title, body) ->
        val haystack = "$title $body".lowercase()
        query.split(Regex("\\s+")).filter { it.isNotEmpty() }.all { haystack.contains(it) }
    }.map { it.first }

// Describes the controls actually wired up in this version, rather than planned features.
private val guideTopics = listOf(
    GuideTopic("start", "Getting started", "Add folders and find your books", listOf(
        "Add a folder" to "Open the library menu (the three lines), tap Add Folder, choose a folder in Android's file picker and grant access. The app scans its subfolders, excluding hidden folders. Scanning can take time; a progress banner appears in the library, and a notification keeps the scan running while the app is in the background. You can pause, resume or stop from the banner. An unreadable book is kept with basic details instead of blocking the rest of the scan.",
        "Supported files" to "The folder scanner finds EPUB, MOBI, AZW, AZW3 and PRC files. EPUBs can be read in the app; the other listed formats appear in To Convert. Other formats are not added by this scanner.",
        "Add more or rescan" to "Use Add Folder again for another folder. After adding files to a saved folder, open the menu and tap Rescan Folders. The app stores access to selected folders; if a folder is moved or access expires, choose it again.",
        "Manage folders" to "Open the menu and tap Manage folders to see saved folders, add more, remove a folder, or open its subfolders. Adding a parent folder includes all its subfolders. Removing a folder removes its library entries, not the ebook files; reading progress and edits for those entries are lost. Removing a subfolder also skips it in future scans so its books do not come back; tap Include again on the same screen, then Rescan Folders, to restore them.",
        "Search the library" to "Type in Search in the menu, then choose a shelf. Search applies to that shelf and matches indexed title, author, publisher, series and tags. Clear the search to see the full shelf. Tap a cover for its details and actions."
    )),
    GuideTopic("shelves", "Shelves and book details", "Favorites, reading status and removal", listOf(
        "Choose shelves" to "Tap a book, then tap the heart on its details page to add it to Favorites or remove it. Tap the heart on a cover in the library to add or remove a favorite without opening the book; it fills red when the book is a favorite and is hidden while you are selecting books. Under Reading shelf, choose Unclassified, To Read, Reading or Finished. Select the matching shelf from the library menu. Favorites is independent of the one reading status.",
        "Reading status" to "You can change the reading shelf yourself without changing saved progress. When an EPUB opens successfully, an Unclassified or To Read book moves to Reading; a Finished book stays Finished when reopened. The reader attempts to mark a Reading book Finished at the end of its final reading-order section, including back matter. End detection is conservative and may miss some layouts, so change the status manually if needed.",
        "Custom shelves" to "In the library menu, tap + Create shelf and give it a name. Open a book and tap Custom shelves to add or remove it; the button appears once at least one shelf exists. A book can belong to multiple custom shelves as well as a reading shelf. Delete beside a custom shelf removes the shelf, not its books or reading progress.",
        "All Books, Recent and To Convert" to "All Books shows visible library entries. Recent shows up to 50 most recently added visible entries, including newly converted EPUBs, in library insertion order; older imports follow their original scan order. To Convert lists visible non-EPUB entries. Counts in the menu exclude hidden originals and are independent of search.",
        "Details and path" to "Book details show available metadata and a Path. Tap the path to try opening its folder in a file manager. If that is not possible, the app copies a readable path instead.",
        "Remove from Library" to "In book details, tap Remove from Library. This removes the library entry, not the source file, and does not ask for confirmation. Its app-only edits, favorite, status and library progress entry are removed too. A rescan can add the file again; keep a separate backup of anything important.",
        "Selection" to "Long-press a cover to enter selection mode; tap covers to select or deselect them. All selects every book on the current shelf matching the active search, including books not yet loaded on screen. Actions lets you move books to a reading shelf, manage custom shelves, change Favorites, share files, or convert non-EPUB books. If none of the selected books are favorites it offers Add to favorites, if all are it offers Remove from favorites, and with a mix it offers both. Favorite changes apply to every selected book after confirmation. In Manage custom shelves, Remove is available for the currently open custom shelf. Press Android Back or tap × to leave selection mode."
    )),
    GuideTopic("reading", "Reading an EPUB", "Open, navigate and find text", listOf(
        "Open and continue" to "Tap a book, then Read Book or Continue. The reader stores your position and progress for a library book; Continue returns to the saved position when available. EPUB layout and navigation depend on the book.",
        "Reader controls" to "Tap the reading area to show or hide the controls. They also hide automatically after a few seconds. The top bar has Back, Find, TOC, Rotate and Aa. Rotate switches quickly between portrait and landscape. The bottom bar has previous/next arrows, a position slider, page/position and percentage. Drag the slider to jump when the book supplies positions.",
        "Chapters and search" to "Tap TOC and choose an entry to jump there. If the book has no table of contents, the app offers reading-order sections instead. Tap Find, enter a word or name, tap Find and select a result. Results depend on the book's searchable text.",
        "Dictionary" to "Select a word in the book and choose Define in the selection menu. The app looks it up online, preferring Italian or English according to the book language. Search / Translate in Browser opens Wiktionary. Availability and definitions depend on the online sources."
    )),
    GuideTopic("reader_settings", "Reading appearance", "Fonts, themes and reading behaviour", listOf(
        "Open Aa" to "While reading, tap the page to show controls, then tap Aa. Swipe the sheet to see all options. Changes are saved as reader preferences and apply to the reader, not the library theme.",
        "Page and type" to "Choose Light, Sepia, Dark, AMOLED or E-Ink. Choose a typeface, including Dyslexic and Accessible, then adjust font size, line spacing and margins. Horizontal rows can be scrolled to reveal more choices; results may vary with the EPUB.",
        "Screen" to "Set orientation to Auto, Portrait or Landscape, or use the Rotate button in the reader top bar for a quick switch. Adjust brightness or use Reset to Auto to return to system brightness. Adjust the warm filter. Fullscreen hides system bars while reading; swipe from the screen edge to temporarily reveal them.",
        "Behaviour" to "Switch volume-key page turning, vertical scroll, keeping the screen awake and the mini footer on or off. With volume-key turning enabled, volume down goes forward and volume up goes back. The footer shows chapter and progress when controls are hidden."
    )),
    GuideTopic("metadata", "Edit book information", "Manual edits and online matching", listOf(
        "Edit locally" to "Tap a book, then Edit. If an EPUB is still being scanned, wait for its metadata scan to finish. Edit title, author, series and book number, tags (separate with semicolons), date, publisher and language. Use Change Cover to choose an image, or Remove to remove the app's cover. Tap Save or Cancel.",
        "Search online" to "In Edit, enter a title or author and tap Auto-Fetch Metadata & Cover. The title and author are sent to online catalogues. Choose a match, review its edition and select which available fields to apply. Apply to draft, then tap Save. A selected cover must finish downloading first. Unchecked fields stay unchanged.",
        "Original file" to "Edits and cover changes are saved in this app's library. The original ebook is not rewritten. When you share an edited EPUB or send it to Kindle, the app prepares a copy that includes the corrected title, author, series, tags, publisher, date, language and cover."
    )),
    GuideTopic("convert", "Convert to EPUB", "MOBI and Kindle-format files", listOf(
        "Conversion" to "Choose To Convert in the menu, tap a non-EPUB book and tap Convert to EPUB. The app attempts to create a separate EPUB, adds it to the library and opens its details. Once a MOBI or AZW3 is successfully converted and linked, its original library entry is hidden by default, not deleted. Conversion can fail or produce imperfect results depending on the source; check the output. Failed conversions remain visible and the source file is kept.",
        "Show or hide originals" to "Open the library menu and tap Converted original visibility. Switch on Show converted originals to reveal linked originals on shelves and include them in counts; switch it off to hide them again. Previously converted books cannot be linked automatically, so their originals remain visible. Hiding an entry never deletes its file.",
        "Where it goes" to "By default, converted EPUBs are in the app's private storage. Share the converted book to save a copy elsewhere. App storage can be lost if the app is uninstalled or its data is cleared. Although Settings displays Pick SD / Folder and Reset, those controls are not connected in this version; do not rely on them to change the destination."
    )),
    GuideTopic("share", "Share and Send to Kindle", "Send a book using another app", listOf(
        "Share" to "Tap a book, then Share, and choose an app from Android's share sheet. The app prepares the book file as an attachment; you finish the action in the chosen app. If you edited the book's details or cover in the app, the attachment is a copy that includes those corrections; the original file is untouched.",
        "Kindle" to "In the library menu, open Settings, enter your Send to Kindle email and tap Save. Open an EPUB's details and tap Send to Kindle; choose an email app in the chooser, check the prepared message and send it yourself. A non-EPUB must first be converted for this button. Delivery depends on your mail app and Amazon's current requirements, including approved sender settings."
    )),
    GuideTopic("settings", "Library settings", "Appearance, maintenance and known limitations", listOf(
        "Appearance" to "Open the library menu and tap Settings. Choose the app theme mode and accent; the accent row scrolls sideways. AMOLED Pitch-Black applies to dark app UI. Tap Done to close Settings.",
        "Other settings" to "Save your Kindle email here. The Reopen Last Book on Launch switch is displayed, but automatic reopening is not connected in this version. The output-folder picker and Clean Duplicates buttons are also not connected. Do not rely on these controls to perform those actions.",
        "Storage" to "The storage summary shows app covers, converted EPUBs and temporary cache. Clear Temp Cache removes temporary working, share and reader copies, not the original source ebooks or the app's converted EPUB folder. Avoid clearing it while an attachment is still being used by another app."
    )),
    GuideTopic("safety", "Storage, safety and troubleshooting", "Permissions, missing books and backups", listOf(
        "What is stored" to "The app keeps library records, metadata edits, favorites, reading statuses, progress and preferences on this device. Folder books remain in their original locations; access depends on Android's folder permission. Back up your original files separately. The app has no visible export for its library edits or shelves.",
        "Uninstall or clear app data" to "Clearing app data or uninstalling can remove library records, preferences, app-only metadata and EPUBs in private app storage. Copy important converted EPUBs out using Share before doing so. Original files in separately chosen folders are not removed by the library's Remove action.",
        "Missing or inaccessible books" to "Check that the file is in a selected folder and still accessible, then use Rescan Folders or add the folder again. If Android reports that folder access was lost, add the folder again and allow access. Clear any active library search and check All Books. Scans may take time.",
        "Scan details" to "If a scan stops, open the scan banner and tap Scan details. The log shows which folder failed and why; include it when asking for help. Background scans may also be stopped by aggressive phone battery settings, so allow notifications and remove battery restrictions for the app.",
        "Book or conversion fails" to "Check that the source file is accessible and intact. The reader opens EPUBs; use Convert for supported non-EPUB entries. Not every source can be converted or read successfully. Keep the source and try another copy or app if needed.",
        "Online search or dictionary fails" to "Check your connection and try again. For metadata, try a shorter title or author and check the edition before saving. No results can also mean the catalogues are unavailable. For dictionary lookup, try a single word or the browser option.",
        "Kindle or sharing fails" to "Check that the file is still accessible and a suitable sharing or email app is installed. For Kindle, save the correct Kindle address, check the attachment and recipient in your mail app, send the message, and review Amazon's sender and delivery requirements."
    ))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserGuideScreen(onClose: () -> Unit) {
    var topicId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val topic = guideTopics.firstOrNull { it.id == topicId }
    val normalizedQuery = query.trim().lowercase()
    val visibleTopics = if (normalizedQuery.isEmpty()) guideTopics else guideTopics.filter { it.matches(normalizedQuery) }
    fun goBack() { if (topicId != null) topicId = null else onClose() }
    BackHandler { goBack() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (topic == null) "User Guide" else topic.title, maxLines = 2) },
            navigationIcon = { TextButton(onClick = { goBack() }) { Text("Back") } }
        )
    }) { padding ->
        key(topicId) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (topic == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Search the guide") },
                        placeholder = { Text("e.g. folders, Kindle, rotate") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(if (normalizedQuery.isEmpty()) "Choose a topic" else "Search results",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    if (normalizedQuery.isEmpty()) {
                        Text("This guide describes the controls available in this version. Use Back to return to the library.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("${visibleTopics.size} topic${if (visibleTopics.size == 1) "" else "s"} match “$query”.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (visibleTopics.isEmpty()) {
                        Text("No topics match that search. Try a shorter word, such as “folder”, “Kindle” or “rotate”.", style = MaterialTheme.typography.bodyMedium)
                    }
                    visibleTopics.forEach { item ->
                        val matches = if (normalizedQuery.isEmpty()) emptyList() else item.matchingSections(normalizedQuery)
                        OutlinedButton(onClick = { topicId = item.id }, modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                                if (matches.isNotEmpty()) {
                                    Text("Matches: " + matches.take(3).joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else {
                    topic.sections.forEach { (sectionTitle, body) ->
                        Text(sectionTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                        Text(body, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { topicId = null }, modifier = Modifier.fillMaxWidth()) { Text("Back to guide topics") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
