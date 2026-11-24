package com.example.myebookapp

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode(val label: String) {
    AUTO("Auto"),
    DARK("Dark"),
    LIGHT("Light");

    fun next(): AppThemeMode = when (this) {
        AUTO -> DARK
        DARK -> LIGHT
        LIGHT -> AUTO
    }
}

enum class AppAccent(
    val label: String,
    val swatchColor: Color,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightContainer: Color,
    val darkContainer: Color
) {
    DYNAMIC("Material You", Color(0xFF7C4DFF), Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFFEADDFF), Color(0xFF4F378B)),
    WIZARD("Wizard Purple", Color(0xFFA855F7), Color(0xFF7E22CE), Color(0xFFC084FC), Color(0xFFF3E8FF), Color(0xFF3B0764)),
    OCEAN("Ocean Blue", Color(0xFF0284C7), Color(0xFF0369A1), Color(0xFF38BDF8), Color(0xFFE0F2FE), Color(0xFF0C4A6E)),
    FOREST("Forest Green", Color(0xFF16A34A), Color(0xFF15803D), Color(0xFF4ADE80), Color(0xFFDCFCE7), Color(0xFF14532D)),
    AMBER("Warm Amber", Color(0xFFD97706), Color(0xFFB45309), Color(0xFFFBBF24), Color(0xFFFEF3C7), Color(0xFF78350F)),
    ROSE("Crimson Rose", Color(0xFFE11D48), Color(0xFFBE123C), Color(0xFFFB7185), Color(0xFFFFE4E6), Color(0xFF881337))
}

object ThemePrefs {
    private const val PREFS_NAME = "my_ebook_theme_prefs"
    private const val KEY_MODE = "app_theme_mode"
    private const val KEY_ACCENT = "app_accent"
    private const val KEY_AMOLED = "app_amoled_dark"
    private const val KEY_AUTO_RESUME = "open_last_book_on_launch"
    private const val KEY_OFFLINE_TRANSLATION = "offline_translation_enabled"
    private const val KEY_SORT = "library_sort"
    private const val KEY_VIEW = "library_view"
    private const val KEY_AUTO_SCAN = "auto_scan_on_open"
    private const val KEY_LAST_AUTO_SCAN = "last_auto_scan"
    private const val KEY_KINDLE_EMAIL = "kindle_email"
    private const val KEY_CONVERT_OUT_URI = "converted_output_tree_uri"

    fun getMode(context: Context): AppThemeMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AppThemeMode.AUTO.name)
        return runCatching { AppThemeMode.valueOf(raw ?: AppThemeMode.AUTO.name) }
            .getOrDefault(AppThemeMode.AUTO)
    }

    fun saveMode(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getAccent(context: Context): AppAccent {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCENT, AppAccent.DYNAMIC.name)
        return runCatching { AppAccent.valueOf(raw ?: AppAccent.DYNAMIC.name) }
            .getOrDefault(AppAccent.DYNAMIC)
    }

    fun saveAccent(context: Context, accent: AppAccent) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCENT, accent.name).apply()
    }

    fun isAmoled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AMOLED, false)

    fun saveAmoled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AMOLED, enabled).apply()
    }

    fun getLibraryView(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_VIEW, "grid") ?: "grid"

    fun saveLibraryView(context: Context, view: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VIEW, view).apply()
    }

    fun getSort(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SORT, "title") ?: "title"

    fun saveSort(context: Context, sort: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SORT, sort).apply()
    }

    fun isAutoScanOnOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_SCAN, false)

    fun saveAutoScanOnOpen(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SCAN, enabled).apply()
    }

    fun lastAutoScan(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_SCAN, 0L)

    fun saveLastAutoScan(context: Context, time: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_AUTO_SCAN, time).apply()
    }

    fun isAutoOpenLastBook(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_RESUME, false)

    fun saveAutoOpenLastBook(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }

    // Retain a previously stored preference, but never enable an unavailable feature.
    fun isOfflineTranslationEnabled(context: Context): Boolean =
        TranslationAvailability.isAvailable && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_TRANSLATION, false)

    fun saveOfflineTranslationEnabled(context: Context, enabled: Boolean) {
        if (enabled && !TranslationAvailability.isAvailable) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OFFLINE_TRANSLATION, enabled).apply()
    }

    fun getKindleEmail(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_KINDLE_EMAIL, "") ?: ""

    fun saveKindleEmail(context: Context, email: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_KINDLE_EMAIL, email.trim()).apply()
    }

    fun getConvertedOutputUri(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONVERT_OUT_URI, null)?.ifBlank { null }

    fun saveConvertedOutputUri(context: Context, uriString: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONVERT_OUT_URI, uriString ?: "").apply()
    }
}

@Composable
fun MyEbookTheme(
    mode: AppThemeMode = AppThemeMode.AUTO,
    accent: AppAccent? = null,
    amoledDark: Boolean? = null,
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resolvedAccent = accent ?: ThemePrefs.getAccent(context)
    val resolvedAmoled = amoledDark ?: ThemePrefs.isAmoled(context)

    val darkTheme = forceDark || when (mode) {
        AppThemeMode.AUTO -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    var colorScheme: ColorScheme = when {
        resolvedAccent == AppAccent.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = resolvedAccent.darkPrimary,
            secondary = resolvedAccent.darkPrimary,
            primaryContainer = resolvedAccent.darkContainer,
            secondaryContainer = resolvedAccent.darkContainer
        )
        else -> lightColorScheme(
            primary = resolvedAccent.lightPrimary,
            secondary = resolvedAccent.lightPrimary,
            primaryContainer = resolvedAccent.lightContainer,
            secondaryContainer = resolvedAccent.lightContainer
        )
    }

    if (darkTheme && resolvedAmoled) {
        // Material 3 draws cards, sheets, the drawer and bars from the surface
        // container family, so all of them must be blacked out, not just background.
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF1A1A1C),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0A0C),
            surfaceContainer = Color(0xFF0E0E10),
            surfaceContainerHigh = Color(0xFF121214),
            surfaceContainerHighest = Color(0xFF161618),
            surfaceVariant = Color(0xFF141416),
            surfaceTint = Color.Black
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            val bgColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = bgColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = bgColor
            window.decorView.setBackgroundColor(bgColor)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
