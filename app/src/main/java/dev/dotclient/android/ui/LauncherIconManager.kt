package dev.dotclient.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class LauncherIcon(val label: String, val componentClass: String) {
    SHIELD("shield", "dev.dotclient.android.LauncherShield"),
    RED_DOT("red dot", "dev.dotclient.android.LauncherRedDot"),
    WORDMARK("dot.", "dev.dotclient.android.LauncherWordmark"),
}

object LauncherIconManager {
    private const val PREFS = "dot_ui"
    private const val KEY = "launcher_icon"

    fun current(context: Context): LauncherIcon {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return LauncherIcon.entries.firstOrNull { it.name == raw } ?: LauncherIcon.SHIELD
    }

    fun apply(context: Context, icon: LauncherIcon) {
        val pm = context.packageManager
        // Enable the target first so the launcher never observes a moment with no entry point.
        pm.setComponentEnabledSetting(
            ComponentName(context, icon.componentClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        LauncherIcon.entries.filterNot { it == icon }.forEach { other ->
            pm.setComponentEnabledSetting(
                ComponentName(context, other.componentClass),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, icon.name).apply()
    }
}
