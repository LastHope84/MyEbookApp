package com.example.myebookapp

import android.content.Context

/** Baseline APK has no engine or model network operations. */
object TranslationRuntime {
    fun isAvailable(): Boolean = false
    suspend fun translate(context: Context, source: String, target: String, text: String): String = unavailable()
    suspend fun routes(context: Context): List<TranslationRoute> = unavailable()
    suspend fun downloadPack(context: Context, source: String, target: String) = unavailable()
    suspend fun sharedRoutes(context: Context, source: String, target: String): List<String> = unavailable()
    suspend fun removePack(context: Context, source: String, target: String): String = unavailable()
    private fun unavailable(): Nothing = throw IllegalStateException("Offline translation is unavailable in this build")
}
