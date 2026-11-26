package com.example.myebookapp
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index("uriString", unique = true), Index("title"), Index("series"), Index("lastOpenedAt"), Index("format"), Index("metadataExtracted"), Index("scanRootUri"), Index("scanParentId")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String, val fileName: String, val format: String,
    val fileSize: Long, val lastModified: Long, val title: String,
    val author: String, val publishingDate: String, val publisher: String,
    val language: String, val coverPath: String?, val lastLocatorJson: String?,
    val progressPercent: Double, val lastOpenedAt: Long, val metadataExtracted: Boolean,
    val series: String? = null, val seriesIndex: Double? = null, val tags: String? = null,
    // Set when the user saved corrections in the app; sharing then exports a rewritten EPUB.
    val metadataEdited: Boolean = false,
    // Full-size copy of a replacement cover, kept only for exported copies.
    val exportCoverPath: String? = null,
    val isFavorite: Boolean = false,
    val readingStatus: String = "NEW",
    // Filled by the scanner so a subfolder can be removed without guessing from its name.
    val scanRootUri: String? = null,
    val scanParentId: String? = null
)

@Fts4(contentEntity = BookEntity::class)
@Entity(tableName = "books_fts")
data class BookFtsEntity(
    val author: String,
    val publisher: String,
    val series: String?,
    val tags: String?,
    val title: String
)

@Entity(tableName = "custom_shelves", indices = [Index(value = ["name"], unique = true)])
data class CustomShelf(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String
)

@Entity(
    tableName = "custom_shelf_books", primaryKeys = ["shelfId", "bookId"],
    foreignKeys = [
        ForeignKey(entity = CustomShelf::class, parentColumns = ["id"], childColumns = ["shelfId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ], indices = [Index("bookId")]
)
data class CustomShelfBook(val shelfId: Long, val bookId: Long)
data class CustomShelfWithCount(val id: Long, val name: String, val bookCount: Int)
data class InitialCount(val initial: String, val bookCount: Int)

// Both endpoints are real book IDs; deleting either endpoint deletes only this link.
@Entity(tableName = "conversion_links", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["sourceBookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["outputBookId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("outputBookId")])
data class ConversionLink(@PrimaryKey val sourceBookId: Long, val outputBookId: Long)

// Saved passages. locatorJson reopens an EPUB quote; pageIndex reopens a PDF one.
@Entity(
    tableName = "quotes",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val text: String,
    val locatorJson: String? = null,
    val pageIndex: Int? = null,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// A library entry the user removed. Folder scans skip it until it is restored
// or added again, so "Remove from Library" stays removed.
@Entity(tableName = "removed_books", indices = [Index("identity")])
data class RemovedBook(
    @PrimaryKey val uriString: String,
    val identity: String,
    val fileName: String,
    val removedAt: Long
)

// A document ID identifies the file even when it is reachable through another root.
fun bookIdentity(uriString: String): String = runCatching {
    val uri = Uri.parse(uriString)
    val authority = uri.authority ?: return@runCatching uriString
    "$authority|${DocumentsContract.getDocumentId(uri)}"
}.getOrDefault(uriString)

data class ExtractedBookMeta(val id: Long, val title: String, val author: String, val publishingDate: String, val publisher: String, val language: String, val coverPath: String?, val series: String?, val seriesIndex: Double?, val tags: String?)
data class BookDuplicateRow(val id: Long, val uriString: String, val fileName: String, val fileSize: Long, val lastOpenedAt: Long, val progressPercent: Double, val hasCover: Boolean)

// Leaves room for other query parameters on SQLite builds with a 999-variable limit.
private const val BULK_ID_CHUNK_SIZE = 500

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1") suspend fun getByIdOnce(id: Long): BookEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConversionLink(link: ConversionLink)
    @Query("DELETE FROM conversion_links WHERE sourceBookId = :sourceBookId") suspend fun removeConversionLink(sourceBookId: Long): Int

    // Call only after successful output writing and indexing, with the exact IDs.
    @Transaction suspend fun recordSuccessfulConversion(sourceBookId: Long, outputBookId: Long) {
        require(sourceBookId != outputBookId) { "Source and output must be different books." }
        val source = requireNotNull(getByIdOnce(sourceBookId)) { "Source book is missing." }
        val output = requireNotNull(getByIdOnce(outputBookId)) { "Output book is missing." }
        require(source.format.uppercase() in setOf("MOBI", "AZW3")) { "Source must be MOBI or AZW3." }
        require(output.format.uppercase() == "EPUB") { "Output must be EPUB." }
        putConversionLink(ConversionLink(sourceBookId, outputBookId))
    }
    @Query("INSERT OR IGNORE INTO custom_shelf_books (shelfId, bookId) SELECT shelfId, :outputBookId FROM custom_shelf_books WHERE bookId = :sourceBookId")
    suspend fun copyCustomShelfMemberships(sourceBookId: Long, outputBookId: Long)

    // Index, copy shelf state only for a newly inserted output, and link as one transaction.
    // An existing output may have user-edited state: never replace or merge it.
    @Transaction suspend fun indexConvertedOutput(sourceBookId: Long, sourceUri: String, entry: BookEntity): BookEntity {
        val source = requireNotNull(getByIdOnce(sourceBookId)) { "Source book is missing." }
        require(source.uriString == sourceUri) { "Source no longer matches the library entry." }
        require(sourceUri != entry.uriString) { "Converted output has the same URI as the source." }
        require(entry.format.uppercase() == "EPUB") { "Output must be EPUB." }
        val insertedId = insertIgnore(entry.copy(isFavorite = source.isFavorite, readingStatus = source.readingStatus))
        val output = requireNotNull(getByUri(entry.uriString)) { "Could not add the converted book to the library." }
        require(output.format.uppercase() == "EPUB") { "Output entry is not EPUB." }
        if (insertedId != -1L) {
            check(output.id == insertedId) { "Output no longer matches the inserted entry." }
            copyCustomShelfMemberships(source.id, output.id)
        }
        if (source.format.uppercase() in setOf("MOBI", "AZW3")) {
            recordSuccessfulConversion(source.id, output.id)
        }
        return output
    }
    @Query("SELECT * FROM custom_shelves ORDER BY name COLLATE NOCASE, id") fun observeCustomShelves(): Flow<List<CustomShelf>>
    @Query("SELECT s.id, s.name, COUNT(CASE WHEN sb.bookId IS NOT NULL AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = sb.bookId)) THEN 1 END) AS bookCount FROM custom_shelves s LEFT JOIN custom_shelf_books sb ON s.id = sb.shelfId GROUP BY s.id, s.name ORDER BY s.name COLLATE NOCASE, s.id") fun observeCustomShelvesWithCounts(showConvertedOriginals: Boolean = false): Flow<List<CustomShelfWithCount>>
    @Query("SELECT shelfId FROM custom_shelf_books WHERE bookId = :bookId") fun observeShelfIdsForBook(bookId: Long): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun createCustomShelf(shelf: CustomShelf): Long
    @Query("DELETE FROM custom_shelves WHERE id = :shelfId") suspend fun deleteCustomShelf(shelfId: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookToCustomShelf(membership: CustomShelfBook): Long
    @Query("DELETE FROM custom_shelf_books WHERE shelfId = :shelfId AND bookId = :bookId") suspend fun removeBookFromCustomShelf(shelfId: Long, bookId: Long): Int
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun customShelfPaged(shelfId: Long, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchCustomShelfPaged(shelfId: Long, q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnoreBatch(books: List<BookEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(book: BookEntity): Long
    @Query("SELECT * FROM books WHERE uriString = :u LIMIT 1") suspend fun getByUri(u: String): BookEntity?

    // Backup/restore: read every row, then replace the library in one transaction.
    @Query("SELECT * FROM books ORDER BY id") suspend fun getAllBooksOnce(): List<BookEntity>
    @Query("SELECT * FROM custom_shelves ORDER BY id") suspend fun getAllCustomShelvesOnce(): List<CustomShelf>
    @Query("SELECT * FROM custom_shelf_books") suspend fun getAllShelfBooksOnce(): List<CustomShelfBook>
    @Query("SELECT * FROM conversion_links") suspend fun getAllConversionLinksOnce(): List<ConversionLink>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreBooks(books: List<BookEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreCustomShelves(shelves: List<CustomShelf>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreShelfBooks(rows: List<CustomShelfBook>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreConversionLinks(links: List<ConversionLink>)
    @Query("DELETE FROM conversion_links") suspend fun deleteAllConversionLinks()
    @Query("DELETE FROM custom_shelf_books") suspend fun deleteAllShelfBooks()
    @Query("DELETE FROM custom_shelves") suspend fun deleteAllCustomShelves()
    @Query("DELETE FROM books") suspend fun deleteAllBooks()

    // FTS4 merges its index segments; prefix searches get faster after scans.
    @Query("INSERT INTO books_fts(books_fts) VALUES('optimize')") suspend fun optimizeSearchIndex()
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1") fun observeById(id: Long): Flow<BookEntity?>
    @Query("UPDATE books SET isFavorite = :favorite WHERE id = :id") suspend fun setFavorite(id: Long, favorite: Boolean): Int
    @Query("UPDATE books SET isFavorite = :favorite WHERE id IN (:ids)") suspend fun setFavoriteForIdsChunk(ids: List<Long>, favorite: Boolean): Int
    @Transaction suspend fun setFavoriteForIds(ids: List<Long>, favorite: Boolean): Int {
        val unique = ids.distinct()
        var count = 0
        for (chunk in unique.chunked(BULK_ID_CHUNK_SIZE)) count += setFavoriteForIdsChunk(chunk, favorite)
        check(count == unique.size) { "Some selected books are no longer in the library." }
        return count
    }
    @Query("UPDATE books SET readingStatus = :status WHERE id = :id AND :status IN ('NEW', 'TBR', 'READING', 'FINISHED')") suspend fun setReadingStatus(id: Long, status: String): Int

    // Automatic transitions change only the shelf, never the ebook or other metadata.
    @Query("UPDATE books SET readingStatus = 'READING' WHERE uriString = :uri AND readingStatus IN ('NEW', 'TBR')")
    suspend fun markStarted(uri: String): Int

    // Do not overwrite a manual NEW/TBR/FINISHED selection when an async event arrives.
    @Query("UPDATE books SET readingStatus = 'FINISHED' WHERE uriString = :uri AND readingStatus = 'READING'")
    suspend fun markFinished(uri: String): Int

    @Query("SELECT * FROM books WHERE id IN (:ids)") suspend fun getByIdsChunk(ids: List<Long>): List<BookEntity>
    @Transaction suspend fun getByIds(ids: List<Long>): List<BookEntity> {
        val books = mutableListOf<BookEntity>()
        for (chunk in ids.distinct().chunked(BULK_ID_CHUNK_SIZE)) books.addAll(getByIdsChunk(chunk))
        return books
    }
    @Query("SELECT id FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getAllIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT uriString FROM books") suspend fun getAllUris(): List<String>
    @Query("UPDATE books SET scanRootUri=:root, scanParentId=:parent WHERE uriString=:uri AND (scanRootUri IS NULL OR scanParentId IS NULL)")
    suspend fun updateScanProvenance(uri: String, root: String, parent: String): Int
    @Transaction suspend fun updateScanProvenanceBatch(items: List<BookEntity>) {
        for (book in items) {
            val root = book.scanRootUri ?: continue
            val parent = book.scanParentId ?: continue
            updateScanProvenance(book.uriString, root, parent)
        }
    }
    @Query("SELECT COUNT(*) FROM books WHERE substr(uriString, 1, length(:prefix)) = :prefix") suspend fun countByUriPrefix(prefix: String): Int
    @Query("DELETE FROM books WHERE substr(uriString, 1, length(:prefix)) = :prefix") suspend fun deleteByUriPrefix(prefix: String): Int
    @Query("SELECT COUNT(*) FROM books WHERE scanRootUri = :root AND scanParentId IN (:parentIds)") suspend fun countByScanFolders(root: String, parentIds: List<String>): Int
    @Query("DELETE FROM books WHERE scanRootUri = :root AND scanParentId IN (:parentIds)") suspend fun deleteByScanFolders(root: String, parentIds: List<String>): Int
    @Query("SELECT id FROM books WHERE isFavorite = 1 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getFavoriteIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id FROM books WHERE readingStatus = :status AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getStatusIds(status: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getCustomShelfBookIds(shelfId: Long, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id, uriString, fileName, fileSize, lastOpenedAt, progressPercent, (coverPath IS NOT NULL) AS hasCover FROM books WHERE fileSize > 0") suspend fun getDuplicateCandidates(): List<BookDuplicateRow>
    @Query("SELECT LOWER(fileName) || '|' || fileSize FROM books WHERE fileSize > 0") suspend fun getAllNameSizeKeys(): List<String>
    @Query("SELECT COUNT(*) FROM books WHERE format IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND metadataExtracted = 0") suspend fun countPendingMetadata(): Int
    @Query("SELECT * FROM books WHERE format IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND metadataExtracted = 0 AND id > :afterId ORDER BY id ASC LIMIT :limit") suspend fun getPendingMetadataAfter(afterId: Long, limit: Int): List<BookEntity>
    @Query("UPDATE books SET metadataExtracted = 1 WHERE id = :id AND metadataExtracted = 0") suspend fun markMetadataFailed(id: Long): Int
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY lastOpenedAt DESC LIMIT 1") suspend fun getLastOpenedOnce(showConvertedOriginals: Boolean = false): BookEntity?
    @Query("SELECT COUNT(*) FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeTotalCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND metadataExtracted = 0 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observePendingMetadataCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeToConvertCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE isFavorite = 1 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeFavoriteCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE readingStatus = :status AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeStatusCount(status: String, showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE (lastOpenedAt > 0 OR id IN (SELECT outputBookId FROM conversion_links)) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeRecentCount(showConvertedOriginals: Boolean = false): Flow<Int>

    // Built-in shelves: format collections and books outside every custom shelf.
    @Query("SELECT COUNT(*) FROM books WHERE format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeFormatCount(formats: List<String>, showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") fun observeUnshelvedCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT * FROM books WHERE format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun formatPaged(formats: List<String>, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND books.format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchFormatPaged(q: String, formats: List<String>, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getFormatIds(formats: List<String>, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND books.format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchFormatIds(q: String, formats: List<String>, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT * FROM books WHERE NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun unshelvedPaged(showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchUnshelvedPaged(q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getUnshelvedIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchUnshelvedIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT * FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY id DESC LIMIT 50") fun recentlyAddedPaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.id IN (SELECT id FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY id DESC LIMIT 50) AND books_fts MATCH :q ORDER BY books.id DESC") fun searchRecentlyAddedPaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY id DESC LIMIT 50") suspend fun getRecentlyAddedIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.id IN (SELECT id FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY id DESC LIMIT 50) AND books_fts MATCH :q ORDER BY books.id DESC") suspend fun searchRecentlyAddedIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY lastOpenedAt DESC LIMIT 1") fun observeLastOpened(showConvertedOriginals: Boolean = false): Flow<BookEntity?>
    @Query("SELECT * FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun allPaged(showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchAllPaged(q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE isFavorite = 1 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun favoritePaged(showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchFavoritePaged(q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE readingStatus = :status AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun statusPaged(status: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchStatusPaged(status: String, q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE (lastOpenedAt > 0 OR id IN (SELECT outputBookId FROM conversion_links)) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :reverse = 1 THEN CASE WHEN lastOpenedAt > 0 THEN lastOpenedAt ELSE lastModified END END ASC, CASE WHEN :reverse = 0 THEN CASE WHEN lastOpenedAt > 0 THEN lastOpenedAt ELSE lastModified END END DESC") fun recentPaged(showConvertedOriginals: Boolean = false, reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE (books.lastOpenedAt > 0 OR books.id IN (SELECT outputBookId FROM conversion_links)) AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :reverse = 1 THEN CASE WHEN books.lastOpenedAt > 0 THEN books.lastOpenedAt ELSE books.lastModified END END ASC, CASE WHEN :reverse = 0 THEN CASE WHEN books.lastOpenedAt > 0 THEN books.lastOpenedAt ELSE books.lastModified END END DESC") fun searchRecentPaged(q: String, showConvertedOriginals: Boolean = false, reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun toConvertPaged(showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) ORDER BY CASE WHEN :sort = 'author' AND :reverse = 0 THEN books.author END COLLATE NOCASE ASC, CASE WHEN :sort = 'author' AND :reverse = 1 THEN books.author END COLLATE NOCASE DESC, CASE WHEN :sort = 'title' AND :reverse = 0 THEN books.title END COLLATE NOCASE ASC, CASE WHEN :sort = 'title' AND :reverse = 1 THEN books.title END COLLATE NOCASE DESC, CASE WHEN :sort = 'recent' AND :reverse = 0 THEN books.lastOpenedAt END DESC, CASE WHEN :sort = 'recent' AND :reverse = 1 THEN books.lastOpenedAt END ASC, books.title COLLATE NOCASE ASC, books.id ASC") fun searchToConvertPaged(q: String, showConvertedOriginals: Boolean = false, sort: String = "title", reverse: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE lastOpenedAt > 0 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getRecentIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id FROM books WHERE format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun getToConvertIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchAllIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchRecentIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchToConvertIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchCustomShelfIds(shelfId: Long, q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchFavoriteIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id))") suspend fun searchStatusIds(status: String, q: String, showConvertedOriginals: Boolean = false): List<Long>

    // Alphabet rail: one row per first letter, ordered exactly like the paged shelf queries above.
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsAll(byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE books.isFavorite = 1 AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsFavorite(byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE books.readingStatus = :status AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsStatus(status: String, byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE books.format IN (:formats) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsFormat(formats: List<String>, byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsCustomShelf(shelfId: Long, byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE NOT EXISTS (SELECT 1 FROM custom_shelf_books sb WHERE sb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsUnshelved(byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("SELECT COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE AS initial, COUNT(*) AS bookCount FROM books WHERE books.format NOT IN ('EPUB', 'CBZ', 'CBR', 'PDF') AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id)) GROUP BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ORDER BY COALESCE(SUBSTR(CASE WHEN :byAuthor = 1 THEN books.author ELSE books.title END, 1, 1), '') COLLATE NOCASE ASC") suspend fun initialCountsToConvert(byAuthor: Boolean, showConvertedOriginals: Boolean = false): List<InitialCount>
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id")
    suspend fun updateExtractedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?)
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id AND metadataExtracted=0")
    suspend fun updatePendingMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?): Int
    @Transaction suspend fun updateExtractedMetadataBatch(items: List<ExtractedBookMeta>) { for (i in items) updatePendingMetadata(i.id, i.title, i.author, i.publishingDate, i.publisher, i.language, i.coverPath, i.series, i.seriesIndex, i.tags) }
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=CASE WHEN :changeCover THEN :c ELSE coverPath END, exportCoverPath=CASE WHEN :changeCover THEN :exportCover ELSE exportCoverPath END, series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1, metadataEdited=1 WHERE id=:id")
    suspend fun saveEditedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, changeCover: Boolean, c: String?, exportCover: String?, s: String?, si: Double?, tags: String?): Int
    @Query("UPDATE books SET lastLocatorJson=:l, progressPercent=:p, lastOpenedAt=:o WHERE uriString=:u") suspend fun updateReadingPosition(u: String, l: String, p: Double, o: Long)
    @Query("UPDATE books SET lastOpenedAt=:o WHERE uriString=:u") suspend fun touchOpened(u: String, o: Long)
    @Query("UPDATE books SET coverPath=NULL WHERE id=:id") suspend fun clearCover(id: Long)
    @Query("DELETE FROM books WHERE id IN (:ids)") suspend fun deleteByIdsChunk(ids: List<Long>)
    @Transaction suspend fun deleteByIds(ids: List<Long>) {
        for (chunk in ids.distinct().chunked(BULK_ID_CHUNK_SIZE)) deleteByIdsChunk(chunk)
    }
    @Query("UPDATE books SET readingStatus = :status WHERE id IN (:ids) AND :status IN ('NEW', 'TBR', 'READING', 'FINISHED')") suspend fun setReadingStatusForIdsChunk(ids: List<Long>, status: String): Int
    @Transaction suspend fun setReadingStatusForIds(ids: List<Long>, status: String): Int {
        require(status in setOf("NEW", "TBR", "READING", "FINISHED"))
        val unique = ids.distinct()
        var count = 0
        for (chunk in unique.chunked(BULK_ID_CHUNK_SIZE)) count += setReadingStatusForIdsChunk(chunk, status)
        check(count == unique.size) { "Some selected books are no longer in the library." }
        return count
    }
    @Transaction suspend fun addBooksToCustomShelf(shelfId: Long, ids: List<Long>) {
        for (id in ids.distinct()) addBookToCustomShelf(CustomShelfBook(shelfId, id))
    }
    @Query("DELETE FROM custom_shelf_books WHERE shelfId = :shelfId AND bookId IN (:ids)") suspend fun removeBooksFromCustomShelfChunk(shelfId: Long, ids: List<Long>): Int
    @Transaction suspend fun removeBooksFromCustomShelf(shelfId: Long, ids: List<Long>) {
        for (chunk in ids.distinct().chunked(BULK_ID_CHUNK_SIZE)) removeBooksFromCustomShelfChunk(shelfId, chunk)
    }
    @Query("DELETE FROM custom_shelf_books WHERE bookId IN (:bookIds)") suspend fun removeBooksFromAllCustomShelvesChunk(bookIds: List<Long>): Int
    @Transaction suspend fun removeBooksFromAllCustomShelves(bookIds: List<Long>): Int {
        var count = 0
        for (chunk in bookIds.distinct().chunked(BULK_ID_CHUNK_SIZE)) count += removeBooksFromAllCustomShelvesChunk(chunk)
        return count
    }
    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteById(id: Long)

    // Quotes belong to a library entry and are removed with it.
    @Insert suspend fun insertQuote(quote: QuoteEntity): Long
    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC") fun observeQuotes(bookId: Long): Flow<List<QuoteEntity>>
    @Query("SELECT COUNT(*) FROM quotes WHERE bookId = :bookId") fun observeQuoteCount(bookId: Long): Flow<Int>
    @Query("DELETE FROM quotes WHERE id = :id") suspend fun deleteQuote(id: Long): Int
    @Query("SELECT * FROM quotes ORDER BY id") suspend fun getAllQuotesOnce(): List<QuoteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreQuotes(quotes: List<QuoteEntity>)
    @Query("DELETE FROM quotes") suspend fun deleteAllQuotes()

    // Removed books: tombstones keep folder scans from re-adding them.
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markRemoved(entries: List<RemovedBook>)
    @Query("SELECT identity FROM removed_books") suspend fun getRemovedIdentities(): List<String>
    @Query("SELECT * FROM removed_books ORDER BY removedAt DESC") suspend fun getRemovedBooks(): List<RemovedBook>
    @Query("DELETE FROM removed_books WHERE uriString = :uriString") suspend fun clearRemoved(uriString: String): Int
    @Query("DELETE FROM removed_books") suspend fun clearAllRemoved(): Int
    @Transaction suspend fun removeBooks(entries: List<RemovedBook>, ids: List<Long>) {
        if (ids.isEmpty()) return
        markRemoved(entries)
        deleteByIds(ids)
    }
}

@Database(entities = [BookEntity::class, BookFtsEntity::class, CustomShelf::class, CustomShelfBook::class, ConversionLink::class, QuoteEntity::class, RemovedBook::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val M12 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_title` ON `books` (`title`)") } }
        private val M23 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex REAL")
            db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN readingStatus TEXT NOT NULL DEFAULT 'NEW'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_series` ON `books` (`series`)")
            db.execSQL("DROP TABLE IF EXISTS books_fts")
            db.execSQL("CREATE VIRTUAL TABLE books_fts USING fts4(content=`books`, author, publisher, series, tags, title)")
        } }
        internal val M34 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `custom_shelves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT COLLATE NOCASE NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_shelves_name` ON `custom_shelves` (`name`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `custom_shelf_books` (`shelfId` INTEGER NOT NULL, `bookId` INTEGER NOT NULL, PRIMARY KEY(`shelfId`, `bookId`), FOREIGN KEY(`shelfId`) REFERENCES `custom_shelves`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_shelf_books_bookId` ON `custom_shelf_books` (`bookId`)")
        } }
        internal val M45 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `conversion_links` (`sourceBookId` INTEGER NOT NULL, `outputBookId` INTEGER NOT NULL, PRIMARY KEY(`sourceBookId`), FOREIGN KEY(`sourceBookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`outputBookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversion_links_outputBookId` ON `conversion_links` (`outputBookId`)")
        } }
        internal val M56 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN scanRootUri TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN scanParentId TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_scanRootUri` ON `books` (`scanRootUri`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_scanParentId` ON `books` (`scanParentId`)")
        } }
        internal val M67 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN metadataEdited INTEGER NOT NULL DEFAULT 0")
        } }
        internal val M78 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN exportCoverPath TEXT")
        } }
        internal val M89 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `quotes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `text` TEXT NOT NULL, `locatorJson` TEXT, `pageIndex` INTEGER, `label` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_bookId` ON `quotes` (`bookId`)")
        } }
        internal val M910 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `removed_books` (`uriString` TEXT NOT NULL, `identity` TEXT NOT NULL, `fileName` TEXT NOT NULL, `removedAt` INTEGER NOT NULL, PRIMARY KEY(`uriString`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_removed_books_identity` ON `removed_books` (`identity`)")
        } }
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "my_ebook_library.db").addMigrations(M12, M23, M34, M45, M56, M67, M78, M89, M910).build().also { INSTANCE = it } }
    }
}
fun toFtsQuery(raw: String): String? {
    val tokens = raw.trim().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "$it*" }
}
suspend fun removeDuplicateBooks(dao: BookDao): Int {
    val rows = dao.getDuplicateCandidates(); val grouped = rows.groupBy { it.fileName.lowercase() to it.fileSize }
    val toDelete = mutableListOf<BookDuplicateRow>()
    for ((_, g) in grouped) { if (g.size <= 1) continue; val s = g.sortedWith(compareByDescending<BookDuplicateRow> { it.lastOpenedAt }.thenByDescending { it.progressPercent }.thenByDescending { it.hasCover }.thenBy { it.id })
        for (i in 1 until s.size) toDelete.add(s[i]) }
    val now = System.currentTimeMillis()
    dao.removeBooks(
        toDelete.map { RemovedBook(it.uriString, bookIdentity(it.uriString), it.fileName, now) },
        toDelete.map { it.id }
    )
    return toDelete.size
}
