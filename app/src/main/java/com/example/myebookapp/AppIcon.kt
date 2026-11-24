package com.example.myebookapp

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon between the colorways declared as activity-alias entries. */
object AppIcon {
    const val CHARCOAL = "charcoal"
    const val CREAM = "cream"
    const val PURPLE = "purple"
    const val LAVENDER = "lavender"

    val all = listOf(CHARCOAL, CREAM, PURPLE, LAVENDER)

    private fun alias(context: Context, variant: String): ComponentName {
        val name = when (variant) {
            CREAM -> "IconCream"
            PURPLE -> "IconPurple"
            LAVENDER -> "IconLavender"
            else -> "IconCharcoal"
        }
        return ComponentName(context.packageName, name)
    }

    fun apply(context: Context, variant: String) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            alias(context, variant),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        all.filter { it != variant }.forEach {
            pm.setComponentEnabledSetting(
                alias(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        ThemePrefs.saveIconVariant(context, variant)
    }

    fun ensureApplied(context: Context) {
        val saved = ThemePrefs.getIconVariant(context)
        val state = runCatching {
            context.packageManager.getComponentEnabledSetting(alias(context, saved))
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) apply(context, saved)
    }
}
