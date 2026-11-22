package com.example.myebookapp

import android.content.Context
import io.github.yinvoker.foxlet.DeleteStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared process-wide engine. All operations, including unload/delete, are serialized. */
object TranslationRuntime {
    private val lock = Mutex()
    private var engine: OfflineTranslationEngine? = null
    fun isAvailable(): Boolean = true

    private suspend fun instance(context: Context): OfflineTranslationEngine =
        engine ?: OfflineTranslationEngine.create(context.applicationContext).also { engine = it }

    suspend fun translate(context: Context, source: String, target: String, text: String): String = lock.withLock {
        check(ThemePrefs.isOfflineTranslationEnabled(context)) { "Offline translation is disabled" }
        instance(context).translate(source, target, text)
    }

    suspend fun routes(context: Context): List<TranslationRoute> = lock.withLock {
        instance(context).availableRoutes().map { route ->
            TranslationRoute(route.source, route.target, route.packs.map { p ->
                TranslationPack(p.model.source, p.model.target, p.model.version, p.model.downloadBytes,
                    p.installations.map { "${it.version} (${it.verificationStatus})" })
            })
        }
    }

    /** The only method that may contact the network; called after explicit user confirmation. */
    suspend fun downloadPack(context: Context, source: String, target: String) = lock.withLock {
        check(ThemePrefs.isOfflineTranslationEnabled(context)) { "Offline translation is disabled" }
        val e = instance(context)
        require(e.route(source, target).size == 1) { "Choose a directional pack, not a pivot route" }
        e.download(source, target)
    }

    suspend fun sharedRoutes(context: Context, source: String, target: String): List<String> = lock.withLock {
        instance(context).routesSharingPack(source, target).map { "${it.source} → ${it.target}" }
    }

    /** Caller must confirm removal and its impact on all sharing routes first. */
    suspend fun removePack(context: Context, source: String, target: String): String = lock.withLock {
        val e = instance(context)
        val unload = e.unloadModels()
        check(unload.allReleased) { "Models are still in use; nothing was removed" }
        val report = e.deletePack(source, target, allowShared = true)
        val failures = report.results.filter { it.status == DeleteStatus.Failed || it.status == DeleteStatus.PartiallyDeleted }
        check(failures.isEmpty()) { "Removal was incomplete: ${failures.joinToString { it.status.toString() }}" }
        "Removed; freed ${report.freedBytes} bytes"
    }
}
