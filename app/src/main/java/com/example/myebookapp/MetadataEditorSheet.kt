package com.example.myebookapp
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEditorBottomSheet(b: BookEntity, onD: () -> Unit, onSave: (EpubEditRequest) -> Unit) {
    val ctx = LocalContext.current; val scp = rememberCoroutineScope(); val sheetS = rememberModalBottomSheetState(true)
    fun ed(r: String?): String = if (r == null || r.equals("Unknown", true)) "" else r

    var title by remember { mutableStateOf(ed(b.title)) }
    var author by remember { mutableStateOf(ed(b.author)) }
    var series by remember { mutableStateOf(ed(b.series)) }
    var sIdx by remember { mutableStateOf(b.seriesIndex?.toString() ?: "") }
    var tags by remember { mutableStateOf(ed(b.tags)) }
    var date by remember { mutableStateOf(ed(b.publishingDate)) }
    var pub by remember { mutableStateOf(ed(b.publisher)) }
    var lang by remember { mutableStateOf(ed(b.language)) }
    var newCov by remember { mutableStateOf<Bitmap?>(null) }
    var remCov by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var downloadingCover by remember { mutableStateOf(false) }
    var decodingCover by remember { mutableStateOf(false) }
    var fetchNotice by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<OnlineBookCandidate>?>(null) }

    val imgP = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            decodingCover = true
            scp.launch {
                try {
                    val decoded = withContext(Dispatchers.IO) { EpubMetadataEditor.decodeSampledImageUri(ctx, u) }
                    if (decoded != null) { newCov = decoded; remCov = false }
                    else fetchNotice = "Could not open the selected cover. Your existing cover was kept."
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fetchNotice = "Could not open the selected cover. Your existing cover was kept."
                } finally { decodingCover = false }
            }
        }
    }

    candidates?.let { found ->
        OnlineMetadataPicker(found, onDismiss = { candidates = null }, onApply = { chosen, fields ->
            if ("title" in fields) title = chosen.title
            if ("author" in fields) author = chosen.author
            if ("date" in fields) date = chosen.publishingDate
            if ("publisher" in fields) pub = chosen.publisher
            if ("language" in fields) lang = chosen.language
            candidates = null
            fetchNotice = "Match applied to the draft. Save to keep your changes."
            if ("cover" in fields && !chosen.coverUrl.isNullOrBlank()) {
                downloadingCover = true
                scp.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) { OnlineMetadataFetcher.downloadCoverBitmap(chosen.coverUrl) }
                        if (bitmap == null) fetchNotice = "Could not download the cover. Your existing cover was kept."
                        else { newCov = bitmap; remCov = false }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fetchNotice = "Could not download the cover. Your existing cover was kept."
                    } finally { downloadingCover = false }
                }
            }
        })
    }

    ModalBottomSheet(onDismissRequest = onD, sheetState = sheetS) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Edit Book Info", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            if (remCov) Box(Modifier.size(100.dp, 140.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) { Text("Removed") }
            else if (newCov != null) Image(newCov!!.asImageBitmap(), null, Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)))
            else if (!b.coverPath.isNullOrBlank()) AsyncImage(ImageRequest.Builder(ctx).data(File(b.coverPath)).build(), null, Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)))
            
            Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !downloadingCover && !decodingCover, onClick = { imgP.launch("image/*") }) { Text("Change Cover") }
                if (!remCov && (newCov != null || b.coverPath != null)) OutlinedButton(enabled = !downloadingCover && !decodingCover, onClick = { newCov = null; remCov = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author(s)") }, modifier = Modifier.fillMaxWidth())
            
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = series, onValueChange = { series = it }, label = { Text("Series Name") }, modifier = Modifier.weight(1.5f))
                OutlinedTextField(value = sIdx, onValueChange = { sIdx = it }, label = { Text("Book #") }, modifier = Modifier.weight(0.5f))
            }

            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags / Genres (use ; )") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pub, onValueChange = { pub = it }, label = { Text("Publisher") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lang, onValueChange = { lang = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(12.dp))
            OutlinedButton(enabled = !searching && !downloadingCover && !decodingCover, onClick = {
                val queryTitle = title.trim()
                val queryAuthor = author.trim()
                if (queryTitle.isBlank() && queryAuthor.isBlank()) {
                    fetchNotice = "Enter a title or author first."
                } else {
                    searching = true
                    fetchNotice = null
                    scp.launch {
                        try {
                            val found = withContext(Dispatchers.IO) { OnlineMetadataFetcher.search(queryTitle, queryAuthor) }
                            if (found.isEmpty()) fetchNotice = "No matches found, or the sources are unavailable. Try a shorter search."
                            else candidates = found
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            fetchNotice = "Online search failed. Please check your connection and try again."
                        } finally { searching = false }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (searching) "Searching online..." else "Auto-Fetch Metadata & Cover")
            }
            Text("Search sends the title and author to online book catalogues. Review a match before applying it.", style = MaterialTheme.typography.bodySmall)
            if (searching || downloadingCover || decodingCover) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            fetchNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
            Text("Changes are saved in this app only. The original book file is not changed.", modifier = Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onD, Modifier.weight(1f)) { Text("Cancel") }
                Button(enabled = !downloadingCover && !decodingCover, onClick = {
                    onSave(EpubEditRequest(title.trim(), author.trim(), date.trim(), pub.trim(), lang.trim(), newCov, remCov, series.trim(), sIdx.toDoubleOrNull(), tags.trim()))
                }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
