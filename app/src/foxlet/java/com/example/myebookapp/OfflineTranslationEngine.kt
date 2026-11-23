package com.example.myebookapp

import android.content.Context
import io.github.yinvoker.foxlet.DownloadProgress
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.ModelDescriptor
import io.github.yinvoker.foxlet.PreparePolicy
import io.github.yinvoker.foxlet.DeleteReport
import io.github.yinvoker.foxlet.UnloadReport
import io.github.yinvoker.foxlet.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Experimental Foxlet 0.5.0 adapter. Only included in an explicitly opted-in build.
 * Create one application-scoped instance and share it; Foxlet permits one client per
 * process. Call shutdown only after all readers/jobs have stopped using it.
 *
 * route() is local metadata only. download() is the only method here that can use
 * the network; translation strictly uses verified local models and never downloads.
 * Progress callbacks originate on Foxlet's IO context, not the UI thread.
 */
class OfflineTranslationEngine private constructor(private val foxlet: Foxlet) {
    data class ModelInfo(
        val source: String,
        val target: String,
        val version: String,
        val downloadBytes: Long,
    )

    /** Metadata snapshot, not a guarantee the files are intact or usable. */
    data class InstallationInfo(
        val version: String,
        val sizeBytes: Long,
        val verificationStatus: VerificationStatus,
    )

    data class PackInfo(val model: ModelInfo, val installations: List<InstallationInfo>)
    data class RouteInfo(val source: String, val target: String, val packs: List<PackInfo>) {
        val usesEnglishPivot: Boolean get() = packs.size == 2
        /** Sum of pinned full-download sizes, not additional bytes needed. */
        val fullDownloadBytes: Long get() = packs.sumOf { it.model.downloadBytes }
        /** Metadata only: call isRouteUsable to verify before presenting as ready. */
        val hasInstallationMetadataForAllPacks: Boolean get() = packs.all { it.installations.isNotEmpty() }
    }
    data class RouteDirection(val source: String, val target: String)

    // Prevent this adapter from deleting/unloading while a translation or download is
    // being prepared or used. Callbacks must not synchronously re-enter this adapter.
    private val operationLock = Mutex()

    companion object {
        suspend fun create(context: Context): OfflineTranslationEngine =
            OfflineTranslationEngine(Foxlet.create(context.applicationContext))
    }

    private suspend fun <T> serialized(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { operationLock.withLock { block() } }

    private fun routeFromCatalog(
        pair: LanguagePair,
        catalog: Map<LanguagePair, ModelDescriptor>,
    ): List<ModelDescriptor>? {
        if (pair.source == pair.target) return null
        catalog[pair]?.let { return listOf(it) }
        if (pair.source == "en" || pair.target == "en") return null
        val first = catalog[LanguagePair(pair.source, "en")] ?: return null
        val second = catalog[LanguagePair("en", pair.target)] ?: return null
        return listOf(first, second)
    }

    /** Pinned catalogue; no remote index and no network. */
    private suspend fun catalog(): Map<LanguagePair, ModelDescriptor> =
        foxlet.models.listBundled().associateBy { it.pair }

    private fun availableDirections(catalog: Map<LanguagePair, ModelDescriptor>): List<RouteDirection> {
        val languages = catalog.keys.flatMap { listOf(it.source, it.target) }.distinct().sorted()
        return languages.flatMap { source ->
            languages.mapNotNull { target ->
                if (routeFromCatalog(LanguagePair(source, target), catalog) == null) null
                else RouteDirection(source, target)
            }
        }
    }

    /** All supported directions, including English-pivot routes; metadata status is unverified. */
    suspend fun availableRoutes(): List<RouteInfo> = serialized {
        val catalog = catalog()
        val installed = foxlet.models.listInstalled().groupBy { it.pair }
        availableDirections(catalog).map { direction ->
            val models = checkNotNull(routeFromCatalog(LanguagePair(direction.source, direction.target), catalog))
            RouteInfo(direction.source, direction.target, models.map { model ->
                PackInfo(model.info(), installed[model.pair].orEmpty().map {
                    InstallationInfo(it.version, it.sizeBytes, it.verificationStatus)
                })
            })
        }
    }

    /** Hash-checks a specific route's installed models; may be slow; never downloads. */
    suspend fun isRouteUsable(source: String, target: String): Boolean = serialized {
        descriptors(source, target).all { foxlet.models.findUsable(it.pair) != null }
    }

    /**
     * Preview all other catalogue routes whose selected direct/pivot path needs this
     * directional pack, even if those routes are not currently installed. This is
     * deliberately conservative: no saved "chosen languages" registry is assumed.
     */
    suspend fun routesSharingPack(source: String, target: String): List<RouteDirection> = serialized {
        sharingPack(LanguagePair(source, target), catalog())
    }

    private fun sharingPack(
        pair: LanguagePair,
        catalog: Map<LanguagePair, ModelDescriptor>,
    ): List<RouteDirection> {
        require(catalog.containsKey(pair)) { "No bundled directional pack for $pair" }
        return availableDirections(catalog).filter { direction ->
            val routePair = LanguagePair(direction.source, direction.target)
            routePair != pair && routeFromCatalog(routePair, catalog)!!.any { it.pair == pair }
        }
    }

    /**
     * Deletes ALL installed versions of this one directional pack, never a whole
     * pivot route. If another possible route shares it, explicit consent is required.
     * Review every DeleteReport result; partial filesystem failures are possible.
     * Resident/loading models are refused by Foxlet; unload explicitly if appropriate.
     */
    suspend fun deletePack(source: String, target: String, allowShared: Boolean = false): DeleteReport = serialized {
        val pair = LanguagePair(source, target)
        val sharing = sharingPack(pair, catalog())
        require(allowShared || sharing.isEmpty()) { "Pack is shared by other routes; inspect routesSharingPack and explicitly allow deletion" }
        foxlet.models.deleteAll(pair)
    }

    /** Only call when readers/jobs are idle. Inspect allReleased before attempting deletion. */
    suspend fun unloadModels(): UnloadReport = serialized { foxlet.translator.unloadModels() }

    /** Direct direction if available; otherwise source -> English -> target. */
    private suspend fun descriptors(source: String, target: String): List<ModelDescriptor> {
        val pair = LanguagePair(source, target)
        require(pair.source != pair.target) { "Source and target must be different" }
        return requireNotNull(routeFromCatalog(pair, catalog())) {
            "No offline model route for ${pair.source} -> ${pair.target}"
        }
    }

    private fun ModelDescriptor.info() = ModelInfo(pair.source, pair.target, version, sizeBytes)

    /** Pinned model versions and estimated full download sizes; does not contact a server. */
    suspend fun route(source: String, target: String): List<ModelInfo> = serialized {
        descriptors(source, target).map { it.info() }
    }

    /**
     * Explicitly allowed network operation. Reuses verified models where possible;
     * verifies downloaded sizes and hashes. A two-hop route can leave the first
     * installed if the second fails. Propagates network/storage/cancellation errors.
     */
    suspend fun download(
        source: String,
        target: String,
        onProgress: (ModelInfo, DownloadProgress) -> Unit = { _, _ -> },
    ) = serialized {
        for (descriptor in descriptors(source, target)) {
            foxlet.models.prepare(descriptor.pair, PreparePolicy.LocalOrBundled) { progress ->
                onProgress(descriptor.info(), progress)
            }
        }
    }

    /**
     * Input order is preserved. No network access, even for absent models; missing
     * models throw Foxlet's ModelNotInstalledException. Send bounded batches rather
     * than an entire book. Native inference may not stop mid-batch on cancellation.
     */
    suspend fun translate(source: String, target: String, texts: List<String>): List<String> = serialized {
        val models = descriptors(source, target).map {
            foxlet.models.prepare(it.pair, PreparePolicy.LocalOnly)
        }
        if (models.size == 1) {
            foxlet.translator.translate(texts, models[0])
        } else {
            foxlet.translator.translatePivot(texts, models[0], models[1])
        }
    }

    suspend fun translate(source: String, target: String, text: String): String =
        translate(source, target, listOf(text)).single()

    suspend fun shutdown() = serialized { foxlet.shutdown() }
}
