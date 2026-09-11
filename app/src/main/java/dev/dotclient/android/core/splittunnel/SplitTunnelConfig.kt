package dev.dotclient.android.core.splittunnel

enum class SplitTunnelMode {
    ALL_APPS,
    EXCLUDE_SELECTED,
    INCLUDE_ONLY_SELECTED,
}

data class SplitTunnelConfig(
    val mode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val packages: Set<String> = emptySet(),
) {
    val normalizedPackages: Set<String>
        get() = packages.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSortedSet()

    val isActive: Boolean
        get() = mode != SplitTunnelMode.ALL_APPS && normalizedPackages.isNotEmpty()
}
