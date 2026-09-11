package dev.dotclient.android.core.splittunnel

import android.content.Context

class SplitTunnelStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): SplitTunnelConfig {
        val mode = preferences.getString(KEY_MODE, null)
            ?.let { stored -> SplitTunnelMode.entries.firstOrNull { it.name == stored } }
            ?: SplitTunnelMode.ALL_APPS
        val packages = preferences.getStringSet(KEY_PACKAGES, emptySet())
            .orEmpty()
            .toSet()

        return SplitTunnelConfig(mode = mode, packages = packages)
    }

    fun save(config: SplitTunnelConfig) {
        preferences.edit()
            .putString(KEY_MODE, config.mode.name)
            .putStringSet(KEY_PACKAGES, config.normalizedPackages)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "dot_split_tunnel"
        private const val KEY_MODE = "mode"
        private const val KEY_PACKAGES = "packages"
    }
}
