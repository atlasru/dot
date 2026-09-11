package dev.dotclient.android.core.splittunnel

import android.content.Context
import android.content.Intent

class InstalledAppRepository(
    private val context: Context,
) {
    data class InstalledApp(
        val packageName: String,
        val label: String,
    )

    fun loadLaunchableApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                InstalledApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifBlank { packageName },
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
