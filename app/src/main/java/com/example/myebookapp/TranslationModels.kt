package com.example.myebookapp

/** Installation status is a metadata snapshot, not a guarantee of model integrity. */
data class TranslationPack(val source: String, val target: String, val version: String,
    val downloadBytes: Long, val installedVersions: List<String>)
data class TranslationRoute(val source: String, val target: String, val packs: List<TranslationPack>)
