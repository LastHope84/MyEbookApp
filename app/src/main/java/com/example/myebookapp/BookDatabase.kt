package com.example.myebookapp
import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index("uriString", unique = true), Index("title"), Index("series"), Index("lastOpenedAt"), Index("format"), Index("metadataExtracted")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String, val fileName: String, val format: String,
    val fileSize: Long, val lastModified: Long, val title: String,
    val author: String, val publishingDate: String, val publisher: String,
    val language: String, val coverPath: String?, val lastLocatorJson: String?,
    val progressPercent: Double, val lastOpenedAt: Long, val metadataExtracted: Boolean,
    val series: String? = null, val seriesIndex: Double? = null, val tags: String? = null,
    val isFavorite: Boolean = false,
    val readingStatus: String = "NEW"
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

data class ExtractedBookMeta(val id: Long, val title: String, val author: String, val publishingDate: String, val publisher: String, val language: String, val coverPath: String?, val series: String?, val seriesIndex: Double?, val tags: String?)
data class BookDuplicateRow(val id: Long, val fileName: String, val fileSize: Long, val lastOpenedAt: Long, val progressPercent: Double, val hasCover: Boolean)

// Leaves room for other query parameters on SQLite builds with a 999-variable limit.
private const val BULK_ID_CHUNK_SIZE = 500

@Dao
interface BookDao {
    @Query("SELECT * FROM custom_shelves ORDER BY name COLLATE NOCASE, id") fun observeCustomShelves(): Flow<List<CustomShelf>>
    @Query("SELECT s.id, s.name, COUNT(sb.bookId) AS bookCount FROM custom_shelves s LEFT JOIN custom_shelf_books sb ON s.id = sb.shelfId GROUP BY s.id, s.name ORDER BY s.name COLLATE NOCASE, s.id") fun observeCustomShelvesWithCounts(): Flow<List<CustomShelfWithCount>>
    @Query("SELECT shelfId FROM custom_shelf_books WHERE bookId = :bookId") fun observeShelfIdsForBook(bookId: Long): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun createCustomShelf(shelf: CustomShelf): Long
    @Query("DELETE FROM custom_shelves WHERE id = :shelfId") suspend fun deleteCustomShelf(shelfId: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookToCustomShelf(membership: CustomShelfBook): Long
    @Query("DELETE FROM custom_shelf_books WHERE shelfId = :shelfId AND bookId = :bookId") suspend fun removeBookFromCustomShelf(shelfId: Long, bookId: Long): Int
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId ORDER BY books.title COLLATE NOCASE, books.id") fun customShelfPaged(shelfId: Long): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q ORDER BY books.title COLLATE NOCASE, books.id") fun searchCustomShelfPaged(shelfId: Long, q: String): PagingSource<Int, BookEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnoreBatch(books: List<BookEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(book: BookEntity): Long
    @Query("SELECT * FROM books WHERE uriString = :u LIMIT 1") suspend fun getByUri(u: String): BookEntity?
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
    @Query("SELECT id FROM books") suspend fun getAllIds(): List<Long>
    @Query("SELECT id FROM books WHERE isFavorite = 1") suspend fun getFavoriteIds(): List<Long>
    @Query("SELECT id FROM books WHERE readingStatus = :status") suspend fun getStatusIds(status: String): List<Long>
    @Query("SELECT bookId FROM custom_shelf_books WHERE shelfId = :shelfId") suspend fun getCustomShelfBookIds(shelfId: Long): List<Long>
    @Query("SELECT id, fileName, fileSize, lastOpenedAt, progressPercent, (coverPath IS NOT NULL) AS hasCover FROM books WHERE fileSize > 0") suspend fun getDuplicateCandidates(): List<BookDuplicateRow>
    @Query("SELECT LOWER(fileName) || '|' || fileSize FROM books WHERE fileSize > 0") suspend fun getAllNameSizeKeys(): List<String>
    @Query("SELECT COUNT(*) FROM books WHERE format = 'EPUB' AND metadataExtracted = 0") suspend fun countPendingEpubs(): Int
    @Query("SELECT * FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 ORDER BY id ASC LIMIT :limit") suspend fun getPendingEpubs(limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC LIMIT 1") suspend fun getLastOpenedOnce(): BookEntity?
    @Query("SELECT COUNT(*) FROM books") fun observeTotalCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format = 'EPUB' AND metadataExtracted = 0") fun observePendingMetadataCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format != 'EPUB'") fun observeToConvertCount(): Flow<Int>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC LIMIT 1") fun observeLastOpened(): Flow<BookEntity?>
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC") fun allPaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q ORDER BY title COLLATE NOCASE ASC") fun searchAllPaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC, id ASC") fun favoritePaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q ORDER BY books.title COLLATE NOCASE ASC, books.id ASC") fun searchFavoritePaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE readingStatus = :status ORDER BY title COLLATE NOCASE ASC, id ASC") fun statusPaged(status: String): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q ORDER BY books.title COLLATE NOCASE ASC, books.id ASC") fun searchStatusPaged(status: String, q: String): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC") fun recentPaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q ORDER BY books.lastOpenedAt DESC") fun searchRecentPaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE format != 'EPUB' ORDER BY title COLLATE NOCASE ASC") fun toConvertPaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q ORDER BY books.title COLLATE NOCASE ASC") fun searchToConvertPaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE lastOpenedAt > 0") suspend fun getRecentIds(): List<Long>
    @Query("SELECT id FROM books WHERE format != 'EPUB'") suspend fun getToConvertIds(): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q") suspend fun searchAllIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q") suspend fun searchRecentIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q") suspend fun searchToConvertIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q") suspend fun searchCustomShelfIds(shelfId: Long, q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q") suspend fun searchFavoriteIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q") suspend fun searchStatusIds(status: String, q: String): List<Long>
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id")
    suspend fun updateExtractedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?)
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id AND metadataExtracted=0")
    suspend fun updatePendingMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?)
    @Transaction suspend fun updateExtractedMetadataBatch(items: List<ExtractedBookMeta>) { for (i in items) updatePendingMetadata(i.id, i.title, i.author, i.publishingDate, i.publisher, i.language, i.coverPath, i.series, i.seriesIndex, i.tags) }
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=CASE WHEN :changeCover THEN :c ELSE coverPath END, series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id")
    suspend fun saveEditedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, changeCover: Boolean, c: String?, s: String?, si: Double?, tags: String?): Int
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
}

@Database(entities = [BookEntity::class, BookFtsEntity::class, CustomShelf::class, CustomShelfBook::class], version = 4, exportSchema = false)
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
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "my_ebook_library.db").addMigrations(M12, M23, M34).build().also { INSTANCE = it } }
    }
}
fun toFtsQuery(raw: String): String? {
    val tokens = raw.trim().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
    return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "$it*" }
}
suspend fun removeDuplicateBooks(dao: BookDao): Int {
    val rows = dao.getDuplicateCandidates(); val grouped = rows.groupBy { it.fileName.lowercase() to it.fileSize }
    val toDelete = mutableListOf<Long>()
    for ((_, g) in grouped) { if (g.size <= 1) continue; val s = g.sortedWith(compareByDescending<BookDuplicateRow> { it.lastOpenedAt }.thenByDescending { it.progressPercent }.thenByDescending { it.hasCover }.thenBy { it.id })
        for (i in 1 until s.size) toDelete.add(s[i].id) }
    toDelete.chunked(500).forEach { dao.deleteByIds(it) }; return toDelete.size
}
