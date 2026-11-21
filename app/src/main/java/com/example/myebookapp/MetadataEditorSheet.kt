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
fun MetadataEditorBottomSheet(b: BookEntity, onD: () -> Unit, onSave: (EpubEditRequest, Boolean) -> Unit) {
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
    var writeF by remember { mutableStateOf(b.format == "EPUB") }
    var newCov by remember { mutableStateOf<Bitmap?>(null) }
    var remCov by remember { mutableStateOf(false) }

    val imgP = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) scp.launch(Dispatchers.IO) { val d = EpubMetadataEditor.decodeSampledImageUri(ctx, u); withContext(Dispatchers.Main) { if (d != null) { newCov = d; remCov = false } } }
    }

    ModalBottomSheet(onDismissRequest = onD, sheetState = sheetS) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) { = 20.dp, vertical = 10.dp), Alignment.CenterHorizontally) {
            Text("Edit Book Info", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            if (remCov) Box(Modifier.size(100.dp, 140.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) { Text("Removed") }
            else if (newCov != null) Image(newCov!!.asImageBitmap(), null, Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)))
            else if (!b.coverPath.isNullOrBlank()) AsyncImage(ImageRequest.Builder(ctx).data(File(b.coverPath)).build(), null, Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)))
            
            Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { imgP.launch("image/*") }) { Text("Change Cover") }
                if (!remCov && (newCov != null || b.coverPath != null)) OutlinedButton(onClick = { newCov = null; remCov = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
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

            if (b.format == "EPUB") {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Write changes to .epub file"); Switch(checked = writeF, onCheckedChange = { writeF = it })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onD, Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { 
                    onSave(EpubEditRequest(title.trim(), author.trim(), date.trim(), pub.trim(), lang.trim(), newCov, remCov, series.trim(), sIdx.toDoubleOrNull(), tags.trim()), writeF)
                }, Modifier.weight(1f)) { Text("Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
