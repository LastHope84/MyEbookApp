package com.example.myebookapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["uriString"], unique = true),
        Index(value = ["lastOpenedAt"]),
        Index(value = ["format"]),
        Index(value = ["metadataExtracted"])
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val fileName: String,
    val format: String, // "EPUB", "MOBI", "AZW3"
    val fileSize: Long,
    val lastModified: Long,
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val coverPath: String?,
    val lastLocatorJson: String?,
    val progressPercent: Double,
    val lastOpenedAt: Long,
    val metadataExtracted: Boolean
)

@Fts4(contentEntity = BookEntity::class)
@Entity(tableName = "books_fts")
data class BookFtsEntity(
    val title: String,
    val author: String,
    val publisher: String,
    val language: String,
    val fileName: String
)

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreBatch(books: List<BookEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(book: BookEntity): Long

    @Query("SELECT * FROM books WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): BookEntity?

    @Query("SELECT * FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingEpubs(limit: Int): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE format = 'EPUB' AND metadataExtracted = 0")
    fun observePendingMetadataCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE format != 'EPUB'")
    fun observeToConvertCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC LIMIT 1")
    fun observeLastOpened(): Flow<BookEntity?>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE ASC")
    fun allPaged(): PagingSource<Int, BookEntity>

    @Query("""
        SELECT books.* FROM books
        JOIN books_fts ON books.id = books_fts.rowid
        WHERE books_fts MATCH :ftsQuery
        ORDER BY books.title COLLATE NOCASE ASC
    """)
    fun searchAllPaged(ftsQuery: String): PagingSource<Int, BookEntity>

    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC")
    fun recentPaged(): PagingSource<Int, BookEntity>

    @Query("""
        SELECT books.* FROM books
        JOIN books_fts ON books.id = books_fts.rowid
        WHERE books.lastOpenedAt > 0 AND books_fts MATCH :ftsQuery
        ORDER BY books.lastOpenedAt DESC
    """)
    fun searchRecentPaged(ftsQuery: String): PagingSource<Int, BookEntity>

    @Query("SELECT * FROM books WHERE format != 'EPUB' ORDER BY title COLLATE NOCASE ASC")
    fun toConvertPaged(): PagingSource<Int, BookEntity>

    @Query("""
        SELECT books.* FROM books
        JOIN books_fts ON books.id = books_fts.rowid
        WHERE books.format != 'EPUB' AND books_fts MATCH :ftsQuery
        ORDER BY books.title COLLATE NOCASE ASC
    """)
    fun searchToConvertPaged(ftsQuery: String): PagingSource<Int, BookEntity>

    @Query("""
        UPDATE books SET
            title = :title,
            author = :author,
            publishingDate = :publishingDate,
            publisher = :publisher,
            language = :language,
            coverPath = COALESCE(:coverPath, coverPath),
            metadataExtracted = 1
        WHERE id = :id
    """)
    suspend fun updateExtractedMetadata(
        id: Long,
        title: String,
        author: String,
        publishingDate: String,
        publisher: String,
        language: String,
        coverPath: String?
    )

    @Query("""
        UPDATE books SET
            lastLocatorJson = :locatorJson,
            progressPercent = :progress,
            lastOpenedAt = :openedAt
        WHERE uriString = :uriString
    """)
    suspend fun updateReadingPosition(
        uriString: String,
        locatorJson: String,
        progress: Double,
        openedAt: Long
    )

    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE uriString = :uriString")
    suspend fun touchOpened(uriString: String, openedAt: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [BookEntity::class, BookFtsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "my_ebook_library.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

fun toFtsQuery(raw: String): String? {
    val tokens = raw.trim()
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}
