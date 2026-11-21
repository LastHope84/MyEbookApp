package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MobiNative {
    init {
        System.loadLibrary("mobiconv")
    }

    external fun unpackToDir(inputPath: String, outputOebpsDir: String): String
}

data class ConvertedEpubOutput(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val lastModified: Long,
    val savedToCustomFolder: Boolean
)

object MobiConverter {

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    fun convertToEpubOutput(
        context: Context,
        sourceUri: Uri,
        sourceFileName: String,
        customOutputTreeUri: Uri? = null
    ): ConvertedEpubOutput {
        val workDir = File(context.cacheDir, "mobi_work_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val inputFile = File(workDir, "input.mobi")
            val inputStream = if (sourceUri.scheme == "file") {
                File(sourceUri.path ?: error("Invalid file path")).inputStream()
            } else {
                context.contentResolver.openInputStream(sourceUri)
            } ?: error("Could not read the selected file.")

            inputStream.use { input ->
                inputFile.outputStream().use { output -> input.copyTo(output) }
            }

            val oebpsDir = File(workDir, "OEBPS").apply { mkdirs() }
            val nativeResult = MobiNative.unpackToDir(inputFile.absolutePath, oebpsDir.absolutePath)
            if (nativeResult != "OK") {
                error(nativeResult.removePrefix("ERR:").ifBlank { "Conversion failed." })
            }

            val opfFile = File(oebpsDir, "content.opf")
            if (!opfFile.exists() || opfFile.length() == 0L) {
                buildFallbackOpf(oebpsDir, opfFile, sourceFileName)
            }

            val cleanBase = sourceFileName
                .substringBeforeLast('.')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "converted_book" }
                .take(100)

            val tempPacked = File(workDir, "$cleanBase.epub")
            packEpubZip(oebpsDir, tempPacked)

            // Try saving to user's chosen SD card / custom output folder first
            if (customOutputTreeUri != null) {
                val created = runCatching {
                    val parentDocId = DocumentsContract.getTreeDocumentId(customOutputTreeUri)
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(customOutputTreeUri, parentDocId)
                    val docUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        "application/epub+zip",
                        "$cleanBase.epub"
                    ) ?: return@runCatching null

                    context.contentResolver.openOutputStream(docUri, "wt")?.use { out ->
                        tempPacked.inputStream().buffered().use { it.copyTo(out) }
                    } ?: return@runCatching null

                    val actualName = EpubParser.getFileName(context, docUri)
                    ConvertedEpubOutput(
                        uri = docUri,
                        fileName = actualName,
                        size = tempPacked.length(),
                        lastModified = System.currentTimeMillis(),
                        savedToCustomFolder = true
                    )
                }.getOrNull()

                if (created != null) return created
            }

            // Default / fallback: save in App Storage
            val outDir = File(context.filesDir, "converted_epubs").apply { mkdirs() }
            var outFile = File(outDir, "$cleanBase.epub")
            var suffix = 2
            while (outFile.exists()) {
                outFile = File(outDir, "${cleanBase}_$suffix.epub")
                suffix++
            }
            tempPacked.copyTo(outFile, overwrite = true)

            return ConvertedEpubOutput(
                uri = Uri.fromFile(outFile),
                fileName = outFile.name,
                size = outFile.length(),
                lastModified = outFile.lastModified(),
                savedToCustomFolder = false
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun convertToEpub(context: Context, sourceUri: Uri, sourceFileName: String): File {
        val out = convertToEpubOutput(context, sourceUri, sourceFileName, null)
        return File(out.uri.path!!)
    }

    private fun packEpubZip(oebpsDir: File, outputEpub: File) {
        ZipOutputStream(outputEpub.outputStream().buffered()).use { zos ->
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimeBytes) }.value
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(CONTAINER_XML.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val files = oebpsDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            for (file in files) {
                if (file.isFile) {
                    zos.putNextEntry(ZipEntry("OEBPS/${file.name}"))
                    file.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun buildFallbackOpf(oebpsDir: File, opfFile: File, sourceFileName: String) {
        val files = oebpsDir.listFiles()?.filter { it.isFile && it.name != "content.opf" }?.sortedBy { it.name } ?: emptyList()
        val title = escapeXml(sourceFileName.substringBeforeLast('.').ifBlank { "Converted Book" })
        val manifestLines = StringBuilder()
        val spineLines = StringBuilder()

        files.forEachIndexed { idx, file ->
            val ext = file.extension.lowercase()
            val mediaType = when (ext) {
                "html", "xhtml", "htm" -> "application/xhtml+xml"
                "css" -> "text/css"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "ncx" -> "application/x-dtbncx+xml"
                else -> "application/octet-stream"
            }
            val id = "item_$idx"
            manifestLines.append("    <item id=\"$id\" href=\"${escapeXml(file.name)}\" media-type=\"$mediaType\"/>\n")
            if (ext == "html" || ext == "xhtml" || ext == "htm") {
                spineLines.append("    <itemref idref=\"$id\"/>\n")
            }
        }

        val opfXml = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="BookId">urn:uuid:converted-${System.currentTimeMillis()}</dc:identifier>
  </metadata>
  <manifest>
$manifestLines  </manifest>
  <spine>
$spineLines  </spine>
</package>
"""
        opfFile.writeText(opfXml, Charsets.UTF_8)
    }

    private fun escapeXml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
