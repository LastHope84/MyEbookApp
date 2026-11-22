package com.example.myebookapp
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

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

// Both endpoints are real book IDs; deleting either endpoint deletes only this link.
@Entity(tableName = "conversion_links", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["sourceBookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["outputBookId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("outputBookId")])
data class ConversionLink(@PrimaryKey val sourceBookId: Long, val outputBookId: Long)

data class ExtractedBookMeta(val id: Long, val title: String, val author: String, val publishingDate: String, val publisher: String, val language: String, val coverPath: String?, val series: String?, val seriesIndex: Double?, val tags: String?)
data class BookDuplicateRow(val id: Long, val fileName: String, val fileSize: Long, val lastOpenedAt: Long, val progressPercent: Double, val hasCover: Boolean)

/** Evidence of a scanner-created row, NOT proof of exclusive ownership or deletion authority.
 * Older/imported/deduplicated rows intentionally have no origin. The SAF IDs are opaque. */
@Entity(tableName = "scan_book_origins", foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
])
data class ScanBookOrigin(
    @PrimaryKey val bookId: Long,
    val rootUri: String,
    val parentDocumentId: String,
    val documentId: String
)

/** Historical observations are not a complete or current provider snapshot. A child can have
 * multiple parents, and a provider can change at any time. Never use sightings alone to delete. */
@Entity(tableName = "scan_sightings", primaryKeys = ["rootUri", "parentDocumentId", "documentId"])
data class ScanSighting(
    val rootUri: String,
    val parentDocumentId: String,
    val documentId: String,
    val isDirectory: Boolean
)

/** Historical positive observations only. directoryPath is a JSON array of opaque directory
 * IDs from the granted root through the observed parent. Not an exhaustive route inventory;
 * neither a row nor its absence says whether an independent/manual owner exists. */
@Entity(tableName = "scan_book_routes", primaryKeys = ["bookId", "rootUri", "documentId", "directoryPath"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["authority", "documentId"])])
data class ScanBookRoute(val bookId: Long, val rootUri: String, val authority: String, val documentId: String, val directoryPath: String)

/** A route with ambiguous identity is not attached to a book. Historical routes are not exhaustive. */
@Entity(tableName = "scan_unresolved_routes", primaryKeys = ["rootUri", "documentId", "directoryPath"])
data class ScanUnresolvedRoute(val rootUri: String, val authority: String, val documentId: String, val directoryPath: String)

/** Forward-only provenance. Absence means unknown, never scan-only. */
@Entity(tableName = "scan_owned_books", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ScanOwnedBook(@PrimaryKey val bookId: Long)
@Entity(tableName = "independent_books", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class IndependentBook(@PrimaryKey val bookId: Long)
@Entity(tableName = "scan_memberships", primaryKeys = ["bookId", "rootUri"], foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ScanMembership(val bookId: Long, val rootUri: String, val seenGeneration: Long)
@Entity(tableName = "hidden_scan_books", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class HiddenScanBook(@PrimaryKey val bookId: Long, val rootUri: String)
/** A generation is valid only during one quiescent, complete inventory sweep. */
@Entity(tableName = "scan_inventory_state")
data class ScanInventoryState(@PrimaryKey val singleton: Int = 1, val generation: Long)
/** Durable configured roots; initialized once from legacy preferences, thereafter Room is authoritative. */
@Entity(tableName = "scan_config_roots")
data class ScanConfigRoot(@PrimaryKey val rootUri: String)
@Entity(tableName = "scan_config_state")
data class ScanConfigState(@PrimaryKey val singleton: Int = 1)
@Entity(tableName = "scan_inventory_roots")
data class ScanInventoryRoot(@PrimaryKey val rootUri: String, val generation: Long, val complete: Boolean)

/** A seen child and, only when eligible, a proposed new book row. */
data class ScannedItem(val sighting: ScanSighting, val book: BookEntity?, val directoryPath: List<String>)
data class ScannedBatchResult(val inserted: Int, val acceptedKeys: Set<String>)
data class ScanIdentityMatch(val documentId: String, val bookId: Long)

/** An exclusion applies only to a specific root grant. IDs are opaque. */
@Entity(tableName = "scan_root_exclusions", primaryKeys = ["rootUri", "documentId"])
data class ScanRootExclusion(val rootUri: String, val documentId: String)

/** Durable app-owned scan configuration, not physical ownership. */
@Entity(tableName = "removed_scan_roots")
data class RemovedScanRoot(@PrimaryKey val rootUri: String, val authority: String, val documentId: String)

/** Global document-identity exclusion, not a URI/path prefix. */
@Entity(tableName = "scan_exclusions", primaryKeys = ["authority", "documentId"])
data class ScanExclusion(val authority: String, val documentId: String)
@Entity(tableName = "scan_allowed_roots")
data class ScanAllowedRoot(@PrimaryKey val uri: String)
data class RemovalBookRow(val id: Long, val uriString: String, val rootUri: String?, val documentId: String?)
data class RemovalCandidate(val book: BookEntity, val origin: ScanBookOrigin)

// Leaves room for other query parameters on SQLite builds with a 999-variable limit.
private const val BULK_ID_CHUNK_SIZE = 500

// Only scanner-built tree document URIs can establish this particular exact identity.
// This validates representation, not current reachability, completeness, or exclusivity.
internal fun verifiedScanRoute(item: ScannedItem, authority: String): ScanBookRoute? {
    val sighting = item.sighting
    val book = item.book ?: return null
    return try {
        val root = Uri.parse(sighting.rootUri)
        val path = item.directoryPath
        if (root.scheme != "content" || authority.isBlank() || root.authority != authority ||
            sighting.isDirectory || sighting.documentId.isEmpty() || path.isEmpty() ||
            path.any { it.isEmpty() } || path.toSet().size != path.size ||
            path.first() != DocumentsContract.getTreeDocumentId(root) ||
            path.last() != sighting.parentDocumentId || sighting.documentId in path ||
            book.uriString != DocumentsContract.buildDocumentUriUsingTree(root, sighting.documentId).toString()) null
        else ScanBookRoute(0, sighting.rootUri, authority, sighting.documentId, JSONArray(path).toString())
    } catch (_: IllegalArgumentException) { null }
}

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
    @Query("SELECT s.id, s.name, COUNT(CASE WHEN sb.bookId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = sb.bookId) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = sb.bookId AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))) THEN 1 END) AS bookCount FROM custom_shelves s LEFT JOIN custom_shelf_books sb ON s.id = sb.shelfId GROUP BY s.id, s.name ORDER BY s.name COLLATE NOCASE, s.id") fun observeCustomShelvesWithCounts(showConvertedOriginals: Boolean = false): Flow<List<CustomShelfWithCount>>
    @Query("SELECT shelfId FROM custom_shelf_books WHERE bookId = :bookId") fun observeShelfIdsForBook(bookId: Long): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun createCustomShelf(shelf: CustomShelf): Long
    @Query("DELETE FROM custom_shelves WHERE id = :shelfId") suspend fun deleteCustomShelf(shelfId: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookToCustomShelf(membership: CustomShelfBook): Long
    @Query("DELETE FROM custom_shelf_books WHERE shelfId = :shelfId AND bookId = :bookId") suspend fun removeBookFromCustomShelf(shelfId: Long, bookId: Long): Int
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE, books.id") fun customShelfPaged(shelfId: Long, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE, books.id") fun searchCustomShelfPaged(shelfId: Long, q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM scan_exclusions") suspend fun getScanExclusions(): List<ScanExclusion>
    @Query("SELECT * FROM scan_exclusions") fun observeScanExclusions(): Flow<List<ScanExclusion>>
    @Query("SELECT uri FROM scan_allowed_roots") suspend fun getAllowedScanRoots(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAllowedScanRoots(roots: List<ScanAllowedRoot>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertScanExclusion(exclusion: ScanExclusion): Long
    @Query("DELETE FROM scan_exclusions WHERE authority = :authority AND documentId = :documentId") suspend fun clearScanExclusionRow(authority: String, documentId: String): Int
    @Query("DELETE FROM scan_allowed_roots") suspend fun clearAllowedScanRoots()
    @Transaction suspend fun clearScanExclusion(authority: String, documentId: String): Int {
        val changed = clearScanExclusionRow(authority, documentId)
        if (getScanExclusions().isEmpty()) clearAllowedScanRoots()
        return changed
    }
    @Query("SELECT books.id AS id, books.uriString AS uriString, scan_book_origins.rootUri AS rootUri, scan_book_origins.documentId AS documentId FROM books LEFT JOIN scan_book_origins ON books.id = scan_book_origins.bookId") suspend fun removalBookRows(): List<RemovalBookRow>
    /** No deletion: SAF does not supply an atomic provider snapshot. */
    @Transaction suspend fun commitFolderExclusion(exclusion: ScanExclusion, roots: List<String>) {
        val existing = getScanExclusions()
        if (existing.isEmpty()) insertAllowedScanRoots(roots.distinct().map(::ScanAllowedRoot))
        else require(roots.all { it in getAllowedScanRoots() }) { "New scan roots require exclusions to be cleared first" }
        invalidateScanInventory()
        insertScanExclusion(exclusion)
    }
    @Transaction suspend fun insertIgnoreBatch(books: List<BookEntity>): List<Long> = books.map { insertIgnore(it) }
    @Insert suspend fun insertScanBookOrigins(origins: List<ScanBookOrigin>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertScanSightings(sightings: List<ScanSighting>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertScanBookRoute(route: ScanBookRoute)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertUnresolvedScanRoute(route: ScanUnresolvedRoute)
    @Query("SELECT * FROM scan_book_routes WHERE bookId = :bookId") suspend fun scanBookRoutes(bookId: Long): List<ScanBookRoute>
    @Query("SELECT DISTINCT documentId, bookId FROM scan_book_routes WHERE authority = :authority AND documentId IN (:documentIds)") suspend fun bookIdsForScanIdentities(authority: String, documentIds: List<String>): List<ScanIdentityMatch>
    @Query("SELECT * FROM scan_unresolved_routes") suspend fun unresolvedScanRoutes(): List<ScanUnresolvedRoute>
    @Query("SELECT * FROM scan_book_origins WHERE bookId = :bookId LIMIT 1") suspend fun getScanBookOrigin(bookId: Long): ScanBookOrigin?
    @Query("SELECT * FROM scan_sightings WHERE rootUri = :rootUri AND parentDocumentId = :parentId") suspend fun getScanSightings(rootUri: String, parentId: String): List<ScanSighting>
    @Query("SELECT COUNT(*) FROM books") suspend fun countAllBooks(): Int
    @Query("SELECT * FROM scan_root_exclusions WHERE rootUri = :rootUri") suspend fun rootExclusions(rootUri: String): List<ScanRootExclusion>
    @Query("SELECT * FROM scan_root_exclusions") fun observeRootExclusions(): Flow<List<ScanRootExclusion>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRootExclusion(row: ScanRootExclusion): Long
    @Query("DELETE FROM scan_root_exclusions WHERE rootUri = :rootUri AND documentId = :documentId") suspend fun clearRootExclusion(rootUri: String, documentId: String): Int

    // Same Room write transaction as batch commits: exclusion wins regardless of the order
    // in which either coroutine began.
    @Transaction suspend fun commitRootExclusion(row: ScanRootExclusion) { invalidateScanInventory(); insertRootExclusion(row) }

    @Query("SELECT rootUri FROM scan_config_roots") suspend fun configuredScanRoots(): List<String>
    @Query("SELECT * FROM scan_config_state WHERE singleton = 1") suspend fun configState(): ScanConfigState?
    @Insert suspend fun insertConfigState(state: ScanConfigState)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertConfigRoots(roots: List<ScanConfigRoot>)
    @Query("DELETE FROM scan_config_roots WHERE rootUri = :root") suspend fun deleteConfigRoot(root: String): Int
    @Transaction suspend fun initializeAndCheckConfig(legacyRoots: List<String>) {
        require(legacyRoots.distinct().size == legacyRoots.size && legacyRoots.none { it.isBlank() })
        if (configState() == null) {
            insertConfigRoots(legacyRoots.map(::ScanConfigRoot))
            insertConfigState(ScanConfigState())
        }
        check(configuredScanRoots().toSet() == legacyRoots.toSet()) { "Root configuration requires reconciliation" }
    }
    @Transaction suspend fun addConfiguredRoot(root: String) {
        check(configState() != null) { "Root inventory is not initialized" }
        invalidateScanInventory()
        insertConfigRoots(listOf(ScanConfigRoot(root)))
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun recordScanOwned(row: ScanOwnedBook)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun recordIndependent(row: IndependentBook)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun recordMembership(row: ScanMembership)
    @Query("DELETE FROM hidden_scan_books WHERE bookId = :bookId") suspend fun unhideBook(bookId: Long)
    @Query("DELETE FROM hidden_scan_books") suspend fun unhideAllScanBooks()
    @Query("SELECT * FROM scan_inventory_state WHERE singleton = 1") suspend fun inventoryState(): ScanInventoryState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInventoryState(state: ScanInventoryState)
    @Query("DELETE FROM scan_inventory_roots") suspend fun clearInventoryRoots()
    @Insert suspend fun insertInventoryRoots(roots: List<ScanInventoryRoot>)
    @Query("SELECT * FROM scan_inventory_roots") suspend fun inventoryRoots(): List<ScanInventoryRoot>
    @Query("UPDATE scan_inventory_roots SET complete = 1 WHERE rootUri = :root AND generation = :generation") suspend fun markInventoryComplete(root: String, generation: Long): Int
    @Query("SELECT COUNT(*) FROM scan_unresolved_routes") suspend fun unresolvedRouteCount(): Int
    @Query("INSERT OR IGNORE INTO hidden_scan_books(bookId, rootUri) SELECT b.id, :root FROM books b JOIN scan_owned_books own ON own.bookId = b.id JOIN scan_memberships target ON target.bookId = b.id AND target.rootUri = :root AND target.seenGeneration = :generation WHERE NOT EXISTS (SELECT 1 FROM independent_books i WHERE i.bookId = b.id) AND NOT EXISTS (SELECT 1 FROM scan_memberships m JOIN scan_inventory_roots r ON r.rootUri = m.rootUri WHERE m.bookId = b.id AND r.rootUri != :root) AND NOT EXISTS (SELECT 1 FROM scan_book_routes route JOIN scan_unresolved_routes u ON u.authority = route.authority AND u.documentId = route.documentId WHERE route.bookId = b.id)") suspend fun hideExclusiveScanMembers(root: String, generation: Long): Int
    @Transaction suspend fun invalidateScanInventory() {
        val next = (inventoryState()?.generation ?: 0L) + 1
        putInventoryState(ScanInventoryState(generation = next))
        clearInventoryRoots()
        unhideAllScanBooks()
    }
    @Transaction suspend fun beginInventorySweep(roots: List<String>): Long {
        require(roots.isNotEmpty() && roots.distinct().size == roots.size && roots.none { it.isBlank() })
        invalidateScanInventory()
        val generation = requireNotNull(inventoryState()).generation
        insertInventoryRoots(roots.map { ScanInventoryRoot(it, generation, false) })
        return generation
    }
    @Transaction suspend fun completeInventoryRoot(root: String, generation: Long) {
        check(inventoryState()?.generation == generation && markInventoryComplete(root, generation) == 1) { "Inventory changed" }
    }
    @Transaction suspend fun detachWithInventory(row: RemovedScanRoot, generation: Long, expectedRoots: List<String>): Int {
        val roots = inventoryRoots()
        check(inventoryState()?.generation == generation && roots.map { it.rootUri }.toSet() == expectedRoots.toSet() && roots.all { it.complete && it.generation == generation }) { "Inventory is incomplete or stale" }
        check(configState() != null && configuredScanRoots().toSet() == expectedRoots.toSet()) { "Root configuration changed" }
        check(row.rootUri in expectedRoots && removedScanRoots().isEmpty() && unresolvedRouteCount() == 0 && getScanExclusions().isEmpty() && roots.all { rootExclusions(it.rootUri).isEmpty() }) { "Unresolved scan evidence" }
        insertRemovedScanRoot(row)
        val hidden = hideExclusiveScanMembers(row.rootUri, generation)
        check(deleteConfigRoot(row.rootUri) == 1) { "Configured root is missing" }
        return hidden
    }
    @Transaction suspend fun restoreInventoryRoot(root: String): Int {
        val changed = clearRemovedScanRoot(root)
        if (changed > 0) {
            check(configState() != null) { "Root inventory is not initialized" }
            insertConfigRoots(listOf(ScanConfigRoot(root)))
            invalidateScanInventory()
        }
        return changed
    }
    @Query("SELECT * FROM removed_scan_roots") suspend fun removedScanRoots(): List<RemovedScanRoot>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRemovedScanRoot(row: RemovedScanRoot)
    @Query("DELETE FROM removed_scan_roots WHERE rootUri = :rootUri") suspend fun clearRemovedScanRoot(rootUri: String): Int

    /** Transactional with scanner batch writes. It never alters any book row. */
    @Transaction suspend fun detachScanRoot(row: RemovedScanRoot) {
        require(row.rootUri.isNotBlank() && row.authority.isNotBlank() && row.documentId.isNotBlank())
        insertRemovedScanRoot(row)
    }

    @Query("SELECT * FROM scan_book_origins") suspend fun allScanBookOrigins(): List<ScanBookOrigin>
    @Query("SELECT * FROM books") suspend fun allRemovalBooks(): List<BookEntity>
    @Query("SELECT COUNT(*) FROM custom_shelf_books WHERE bookId = :id") suspend fun shelfCountForRemoval(id: Long): Int
    @Query("SELECT COUNT(*) FROM conversion_links WHERE sourceBookId = :id OR outputBookId = :id") suspend fun conversionCountForRemoval(id: Long): Int
    // No folder-removal row deletion primitive: scan origin and repeated SAF listings
    // cannot prove exclusive ownership at commit time. Keep existing rows until a
    // separately validated, non-destructive ownership model is available.

    @Transaction suspend fun insertScannedBatch(items: List<ScannedItem>, @Suppress("UNUSED_PARAMETER") knownKeys: Set<String>, authority: String, inventoryGeneration: Long? = null): ScannedBatchResult {
        if (items.isEmpty()) return ScannedBatchResult(0, emptySet())
        if (inventoryGeneration != null) check(inventoryState()?.generation == inventoryGeneration) { "Inventory changed" }
        val root = items.first().sighting.rootUri
        require(items.all { it.sighting.rootUri == root && it.directoryPath.isNotEmpty() && it.directoryPath.last() == it.sighting.parentDocumentId })
        val blocked = rootExclusions(root).map { it.documentId }.toSet() +
            getScanExclusions().filter { it.authority == authority }.map { it.documentId } +
            removedRootBlocks(root, authority, removedScanRoots())
        val allowed = items.filter { item ->
            item.directoryPath.none { it in blocked } &&
                !(item.sighting.isDirectory && item.sighting.documentId in blocked)
        }
        insertScanSightings(allowed.map { it.sighting })
        // One ID lookup set per batch avoids one full route-table scan per book.
        // Include earlier observations made in this same transaction in the in-memory map.
        val documentIds = allowed.mapNotNull { verifiedScanRoute(it, authority)?.documentId }.distinct()
        val identityIdsByDocument = mutableMapOf<String, MutableSet<Long>>()
        for (chunk in documentIds.chunked(BULK_ID_CHUNK_SIZE)) {
            for (match in bookIdsForScanIdentities(authority, chunk)) {
                identityIdsByDocument.getOrPut(match.documentId) { mutableSetOf() }.add(match.bookId)
            }
        }
        val origins = mutableListOf<ScanBookOrigin>()
        var inserted = 0
        for (item in allowed) {
            val book = item.book ?: continue
            val route = verifiedScanRoute(item, authority)
            // URI matches include legacy/manual rows. Never attach conflicting identity evidence.
            val exact = getByUri(book.uriString)
            val identityIds = if (route != null) identityIdsByDocument[route.documentId].orEmpty() else emptySet()
            val candidateIds = (identityIds + listOfNotNull(exact?.id)).distinct()
            if (route != null && candidateIds.size > 1) {
                insertUnresolvedScanRoute(ScanUnresolvedRoute(route.rootUri, route.authority, route.documentId, route.directoryPath))
                continue
            }
            if (exact != null) {
                if (route != null) {
                    insertScanBookRoute(route.copy(bookId = exact.id))
                    recordMembership(ScanMembership(exact.id, root, inventoryGeneration ?: 0L))
                    unhideBook(exact.id)
                    identityIdsByDocument.getOrPut(route.documentId) { mutableSetOf() }.add(exact.id)
                }
                continue
            }
            if (route != null && candidateIds.size == 1) {
                insertScanBookRoute(route.copy(bookId = candidateIds.single()))
                recordMembership(ScanMembership(candidateIds.single(), root, inventoryGeneration ?: 0L))
                unhideBook(candidateIds.single())
                continue
            }
            // Equal filenames and sizes do not establish document identity.
            val id = insertBookRaw(book)
            if (id == -1L) {
                val matched = getByUri(book.uriString)
                if (route != null && matched != null) {
                    insertScanBookRoute(route.copy(bookId = matched.id))
                    recordMembership(ScanMembership(matched.id, root, inventoryGeneration ?: 0L))
                    unhideBook(matched.id)
                    identityIdsByDocument.getOrPut(route.documentId) { mutableSetOf() }.add(matched.id)
                } else if (route != null) insertUnresolvedScanRoute(ScanUnresolvedRoute(route.rootUri, route.authority, route.documentId, route.directoryPath))
            }
            if (id == -1L) continue
            inserted++
            // A new row can be kept even when its claimed scan identity is invalid.
            // Neither a route nor an origin may be inferred in that case.
            if (route != null) {
                insertScanBookRoute(route.copy(bookId = id))
                recordScanOwned(ScanOwnedBook(id))
                recordMembership(ScanMembership(id, root, inventoryGeneration ?: 0L))
                identityIdsByDocument.getOrPut(route.documentId) { mutableSetOf() }.add(id)
                val sighting = item.sighting
                origins.add(ScanBookOrigin(id, sighting.rootUri, sighting.parentDocumentId, sighting.documentId))
            }
        }
        if (origins.isNotEmpty()) insertScanBookOrigins(origins)
        return ScannedBatchResult(inserted, emptySet())
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBookRaw(book: BookEntity): Long
    @Transaction suspend fun insertIgnore(book: BookEntity): Long {
        val id = insertBookRaw(book)
        val actual = if (id == -1L) getByUri(book.uriString)?.id else id
        if (actual != null) { recordIndependent(IndependentBook(actual)); unhideBook(actual) }
        return id
    }
    @Query("SELECT * FROM books WHERE uriString = :u LIMIT 1") suspend fun getByUri(u: String): BookEntity?
    @Query("SELECT * FROM books WHERE id = :id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) LIMIT 1") fun observeById(id: Long): Flow<BookEntity?>
    @Query("UPDATE books SET isFavorite = :favorite WHERE id = :id") suspend fun setFavorite(id: Long, favorite: Boolean): Int
    @Query("UPDATE books SET isFavorite = :favorite WHERE id IN (:ids)") suspend fun setFavoriteForIdsChunk(ids: List<Long>, favorite: Boolean): Int
    @Transaction suspend fun setFavoriteForIds(ids: List<Long>, favorite: Boolean): Int {
        val unique = ids.distinct()
        var count = 0
        for (chunk in unique.chunked(BULK_ID_CHUNK_SIZE)) count += setFavoriteForIdsChunk(chunk, favorite)
        check(count == unique.size) { "Some selected books are no longer in the library." }
        return count
    }
    @Query("UPDATE books SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE id IN (:ids)") suspend fun toggleFavoritesChunk(ids: List<Long>): Int
    @Transaction suspend fun toggleFavorites(ids: List<Long>): Int {
        val unique = ids.distinct()
        var count = 0
        for (chunk in unique.chunked(BULK_ID_CHUNK_SIZE)) count += toggleFavoritesChunk(chunk)
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

    @Query("SELECT * FROM books WHERE id IN (:ids) AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id)") suspend fun getByIdsChunk(ids: List<Long>): List<BookEntity>
    @Transaction suspend fun getByIds(ids: List<Long>): List<BookEntity> {
        val books = mutableListOf<BookEntity>()
        for (chunk in ids.distinct().chunked(BULK_ID_CHUNK_SIZE)) books.addAll(getByIdsChunk(chunk))
        return books
    }
    @Query("SELECT id FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getAllIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id FROM books WHERE isFavorite = 1 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getFavoriteIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id FROM books WHERE readingStatus = :status AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getStatusIds(status: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId WHERE custom_shelf_books.shelfId = :shelfId AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getCustomShelfBookIds(shelfId: Long, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id, fileName, fileSize, lastOpenedAt, progressPercent, (coverPath IS NOT NULL) AS hasCover FROM books WHERE fileSize > 0 AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id)") suspend fun getDuplicateCandidates(): List<BookDuplicateRow>
    @Query("SELECT LOWER(fileName) || '|' || fileSize FROM books WHERE fileSize > 0 AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id)") suspend fun getAllNameSizeKeys(): List<String>
    @Query("SELECT COUNT(*) FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id)") suspend fun countPendingEpubs(): Int
    @Query("SELECT * FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) ORDER BY id ASC LIMIT :limit") suspend fun getPendingEpubs(limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 AND id > :afterId AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) ORDER BY id ASC LIMIT :limit") suspend fun getPendingEpubsAfter(afterId: Long, limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY lastOpenedAt DESC LIMIT 1") suspend fun getLastOpenedOnce(showConvertedOriginals: Boolean = false): BookEntity?
    @Query("SELECT COUNT(*) FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") fun observeTotalCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format = 'EPUB' AND metadataExtracted = 0 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") fun observePendingMetadataCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE format != 'EPUB' AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") fun observeToConvertCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE isFavorite = 1 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") fun observeFavoriteCount(showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT COUNT(*) FROM books WHERE readingStatus = :status AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") fun observeStatusCount(status: String, showConvertedOriginals: Boolean = false): Flow<Int>
    @Query("SELECT * FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY id DESC LIMIT 50") fun recentlyAddedPaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.id IN (SELECT id FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY id DESC LIMIT 50) AND books_fts MATCH :q ORDER BY books.id DESC") fun searchRecentlyAddedPaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY id DESC LIMIT 50") suspend fun getRecentlyAddedIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.id IN (SELECT id FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY id DESC LIMIT 50) AND books_fts MATCH :q ORDER BY books.id DESC") suspend fun searchRecentlyAddedIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY lastOpenedAt DESC LIMIT 1") fun observeLastOpened(showConvertedOriginals: Boolean = false): Flow<BookEntity?>
    @Query("SELECT * FROM books WHERE (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY title COLLATE NOCASE ASC") fun allPaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE ASC") fun searchAllPaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE isFavorite = 1 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY title COLLATE NOCASE ASC, id ASC") fun favoritePaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE ASC, books.id ASC") fun searchFavoritePaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE readingStatus = :status AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY title COLLATE NOCASE ASC, id ASC") fun statusPaged(status: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE ASC, books.id ASC") fun searchStatusPaged(status: String, q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY lastOpenedAt DESC") fun recentPaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.lastOpenedAt DESC") fun searchRecentPaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE format != 'EPUB' AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY title COLLATE NOCASE ASC") fun toConvertPaged(showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT books.* FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId)))) ORDER BY books.title COLLATE NOCASE ASC") fun searchToConvertPaged(q: String, showConvertedOriginals: Boolean = false): PagingSource<Int, BookEntity>
    @Query("SELECT id FROM books WHERE lastOpenedAt > 0 AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getRecentIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT id FROM books WHERE format != 'EPUB' AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun getToConvertIds(showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchAllIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.lastOpenedAt > 0 AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchRecentIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.format != 'EPUB' AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchToConvertIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN custom_shelf_books ON books.id = custom_shelf_books.bookId JOIN books_fts ON books.id = books_fts.rowid WHERE custom_shelf_books.shelfId = :shelfId AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchCustomShelfIds(shelfId: Long, q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.isFavorite = 1 AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchFavoriteIds(q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("SELECT books.id FROM books JOIN books_fts ON books.id = books_fts.rowid WHERE books.readingStatus = :status AND books_fts MATCH :q AND (NOT EXISTS (SELECT 1 FROM hidden_scan_books hb WHERE hb.bookId = books.id) AND (:showConvertedOriginals OR NOT EXISTS (SELECT 1 FROM conversion_links cl WHERE cl.sourceBookId = books.id AND NOT EXISTS (SELECT 1 FROM hidden_scan_books hc WHERE hc.bookId = cl.outputBookId))))") suspend fun searchStatusIds(status: String, q: String, showConvertedOriginals: Boolean = false): List<Long>
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id")
    suspend fun updateExtractedMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?)
    @Query("UPDATE books SET title=:t, author=:a, publishingDate=:d, publisher=:p, language=:l, coverPath=COALESCE(:c, coverPath), series=:s, seriesIndex=:si, tags=:tags, metadataExtracted=1 WHERE id=:id AND metadataExtracted=0")
    suspend fun updatePendingMetadata(id: Long, t: String, a: String, d: String, p: String, l: String, c: String?, s: String?, si: Double?, tags: String?): Int
    @Query("SELECT COUNT(*) FROM books WHERE coverPath=:path") suspend fun countCoverReferences(path: String): Int
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

@Database(entities = [BookEntity::class, BookFtsEntity::class, CustomShelf::class, CustomShelfBook::class, ConversionLink::class, ScanBookOrigin::class, ScanSighting::class, ScanExclusion::class, ScanAllowedRoot::class, ScanRootExclusion::class, ScanBookRoute::class, ScanUnresolvedRoute::class, RemovedScanRoot::class, ScanOwnedBook::class, IndependentBook::class, ScanMembership::class, HiddenScanBook::class, ScanInventoryState::class, ScanInventoryRoot::class, ScanConfigRoot::class, ScanConfigState::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        internal val M1011 = object : Migration(10, 11) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_scan_book_routes_authority_documentId`")
            db.execSQL("CREATE INDEX `index_scan_book_routes_authority_documentId` ON `scan_book_routes` (`authority`, `documentId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_owned_books` (`bookId` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `independent_books` (`bookId` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_memberships` (`bookId` INTEGER NOT NULL, `rootUri` TEXT NOT NULL, `seenGeneration` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `rootUri`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `hidden_scan_books` (`bookId` INTEGER NOT NULL, `rootUri` TEXT NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_config_roots` (`rootUri` TEXT NOT NULL, PRIMARY KEY(`rootUri`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_config_state` (`singleton` INTEGER NOT NULL, PRIMARY KEY(`singleton`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_inventory_state` (`singleton` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`singleton`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_inventory_roots` (`rootUri` TEXT NOT NULL, `generation` INTEGER NOT NULL, `complete` INTEGER NOT NULL, PRIMARY KEY(`rootUri`))")
        } }
        internal val M910 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `removed_scan_roots` (`rootUri` TEXT NOT NULL, `authority` TEXT NOT NULL, `documentId` TEXT NOT NULL, PRIMARY KEY(`rootUri`))")
        } }
        // No backfill: older origins/sightings cannot establish exact full routes or manual ownership.
        internal val M89 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_book_routes` (`bookId` INTEGER NOT NULL, `rootUri` TEXT NOT NULL, `authority` TEXT NOT NULL, `documentId` TEXT NOT NULL, `directoryPath` TEXT NOT NULL, PRIMARY KEY(`bookId`, `rootUri`, `documentId`, `directoryPath`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_book_routes_authority_documentId` ON `scan_book_routes` (`authority`, `documentId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_unresolved_routes` (`rootUri` TEXT NOT NULL, `authority` TEXT NOT NULL, `documentId` TEXT NOT NULL, `directoryPath` TEXT NOT NULL, PRIMARY KEY(`rootUri`, `documentId`, `directoryPath`))")
        } }
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
        internal val M56 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_book_origins` (`bookId` INTEGER NOT NULL, `rootUri` TEXT NOT NULL, `parentDocumentId` TEXT NOT NULL, `documentId` TEXT NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_sightings` (`rootUri` TEXT NOT NULL, `parentDocumentId` TEXT NOT NULL, `documentId` TEXT NOT NULL, `isDirectory` INTEGER NOT NULL, PRIMARY KEY(`rootUri`, `parentDocumentId`, `documentId`))")
        } }
        internal val M67 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_exclusions` (`authority` TEXT NOT NULL, `documentId` TEXT NOT NULL, PRIMARY KEY(`authority`, `documentId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_allowed_roots` (`uri` TEXT NOT NULL, PRIMARY KEY(`uri`))")
        } }
        internal val M78 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `scan_root_exclusions` (`rootUri` TEXT NOT NULL, `documentId` TEXT NOT NULL, PRIMARY KEY(`rootUri`, `documentId`))")
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
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "my_ebook_library.db").addMigrations(M12, M23, M34, M45, M56, M67, M78, M89, M910, M1011).build().also { INSTANCE = it } }
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
