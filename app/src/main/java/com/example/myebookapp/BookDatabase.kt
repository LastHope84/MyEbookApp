package com.example.myebookapp
import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index("uriString", unique = true), Index("title"), Index("series"), Index("lastOpenedAt")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String, val fileName: String, val format: String,
    val fileSize: Long, val lastModified: Long, val title: String,
    val author: String, val publishingDate: String, val publisher: String,
    val language: String, val coverPath: String?, val lastLocatorJson: String?,
    val progressPercent: Double, val lastOpenedAt: Long, val metadataExtracted: Boolean,
    val series: String? = null, val seriesIndex: Double? = null, val tags: String? = null,
    val isFavorite: Boolean = false, val readingStatus: String = "NEW"
)
@Fts4(contentEntity = BookEntity::class)
@Entity(tableName = "books_fts")
data class BookFtsEntity(val title: String, val author: String, val publisher: String, val series: String?, val tags: String?)
data class ExtractedBookMeta(val id: Long, val title: String, val author: String, val publishingDate: String, val publisher: String, val language: String, val coverPath: String?, val series: String?, val seriesIndex: Double?, val tags: String?)
data class BookDuplicateRow(val id: Long, val fileName: String, val fileSize: Long, val lastOpenedAt: Long, val progressPercent: Double, val hasCover: Boolean)

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnoreBatch(books: List<BookEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(book: BookEntity): Long
    @Query("SELECT * FROM books WHERE uriString = :u LIMIT 1") suspend fun getByUri(u: String): BookEntity?
    @Query("SELECT * FROM books WHERE id IN (:ids)") suspend fun getByIds(ids: List<Long>): List<BookEntity>
    @Query("SELECT id FROM books") suspend fun getAllIds(): List<Long>
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
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC") fun recentPaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q ORDER BY books.lastOpenedAt DESC") fun searchRecentPaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE format != 'EPUB' ORDER BY title COLLATE NOCASE ASC") fun toConvertPaged(): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q ORDER BY books.title COLLATE NOCASE ASC") fun searchToConvertPaged(q: String): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE lastOpenedAt > 0") suspend fun getRecentIds(): List<Long>
    @Query("SELECT id FROM books WHERE format != 'EPUB'") suspend fun getToConvertIds(): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q") suspend fun searchAllIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q") suspend fun searchRecentIds(q: String): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q") suspend fun searchToConvertIds(q: String): List<Long>
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id")
    suspend fun updateExtractedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?)
    @Transaction suspend fun updateExtractedMetadataBatch(items: List<ExtractedBookMeta>) { for (i in items) updateExtractedMetadata(i.id, i.title, i.author, i.publishingDate, i.publisher, i.language, i.coverPath, i.series, i.seriesIndex, i.tags) }
    @Query("UPDATE books SET lastLocatorJson=:l, progressPercent=:p, lastOpenedAt=:o WHERE uriString=:u") suspend fun updateReadingPosition(u: String, l: String, p: Double, o: Long)
    @Query("UPDATE books SET lastOpenedAt=:o WHERE uriString=:u") suspend fun touchOpened(u: String, o: Long)
    @Query("UPDATE books SET coverPath=NULL WHERE id=:id") suspend fun clearCover(id: Long)
    @Query("DELETE FROM books WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteById(id: Long)
}
@Database(entities = [BookEntity::class, BookFtsEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val M12 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_title` ON `books` (`title`)") } }
        private val M23 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN series TEXT"); db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex REAL")
            db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT"); db.execSQL("ALTER TABLE books ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN readingStatus TEXT NOT NULL DEFAULT 'NEW'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_series` ON `books` (`series`)")
            db.execSQL("DROP TABLE IF EXISTS books_fts"); db.execSQL("CREATE VIRTUAL TABLE books_fts USING fts4(content='books', title, author, publisher, series, tags)")
        } }
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "my_ebook_library.db").addMigrations(M12, M23).build().also { INSTANCE = it } }
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
