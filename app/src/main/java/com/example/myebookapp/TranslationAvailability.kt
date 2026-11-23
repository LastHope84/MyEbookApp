package com.example.myebookapp

// The standard build supplies a fail-closed stub; the separate opt-in Foxlet build
// supplies the experimental runtime. This does not imply a model is installed.
internal object TranslationAvailability {
    val isAvailable: Boolean get() = TranslationRuntime.isAvailable()
}
