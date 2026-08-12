from pathlib import Path

root = Path(__file__).resolve().parents[1]

def write(path: str, content: str):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")

def replace(path: str, old: str, new: str):
    target = root / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")

# Version
replace("app/build.gradle.kts", 'versionCode = 14\n        versionName = "0.0.14"', 'versionCode = 15\n        versionName = "0.0.15"')

# Launcher aliases. MainActivity remains enabled for Quick Settings preferences and explicit intents.
manifest = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="dot."
        android:supportsRtl="true"
        android:theme="@style/Theme.Dot"
        android:usesCleartextTraffic="false">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <activity-alias
            android:name=".LauncherShield"
            android:enabled="true"
            android:exported="true"
            android:icon="@mipmap/ic_launcher"
            android:roundIcon="@mipmap/ic_launcher_round"
            android:label="dot."
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>

        <activity-alias
            android:name=".LauncherRedDot"
            android:enabled="false"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_red_dot"
            android:roundIcon="@mipmap/ic_launcher_red_dot"
            android:label="dot."
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>

        <activity-alias
            android:name=".LauncherWordmark"
            android:enabled="false"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_wordmark"
            android:roundIcon="@mipmap/ic_launcher_wordmark"
            android:label="dot."
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>

        <service
            android:name=".vpn.DotVpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User initiated VLESS VPN tunnel" />
        </service>

        <service
            android:name=".vpn.DotQuickTileService"
            android:exported="true"
            android:icon="@drawable/ic_qs_dot"
            android:label="dot."
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
            <meta-data
                android:name="android.service.quicksettings.ACTIVE_TILE"
                android:value="true" />
            <meta-data
                android:name="android.service.quicksettings.TOGGLEABLE_TILE"
                android:value="true" />
        </service>
    </application>
</manifest>
'''
write("app/src/main/AndroidManifest.xml", manifest)

# Launcher icon foregrounds recreated from the supplied pixel-art references.
write("app/src/main/res/drawable/ic_launcher_red_dot_foreground.xml", '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF1018" android:pathData="M48,42h12v6h6v12h-6v6h-12v-6h-6v-12h6z" />
</vector>
''')
write("app/src/main/res/drawable/ic_launcher_wordmark_foreground.xml", '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#F7F7F7" android:pathData="M22,45h5v5h10v-10h5v25h-15v-5h-5zM27,50v10h10v-10zM47,50h5v-5h10v5h5v10h-5v5h-10v-5h-5zM52,50v10h10v-10zM72,40h5v10h10v5h-10v10h-5zM82,60h5v5h-5z" />
    <path android:fillColor="#FF1018" android:pathData="M92,60h5v5h-5z" />
</vector>
''')
write("app/src/main/res/mipmap-anydpi-v26/ic_launcher_red_dot.xml", '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_red_dot_foreground" />
</adaptive-icon>
''')
write("app/src/main/res/mipmap-anydpi-v26/ic_launcher_wordmark.xml", '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_wordmark_foreground" />
</adaptive-icon>
''')

write("app/src/main/java/dev/dotclient/android/ui/LauncherIconManager.kt", r'''package dev.dotclient.android.ui

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
''')

write("app/src/main/java/dev/dotclient/android/ui/NodeLatencyTester.kt", r'''package dev.dotclient.android.ui

import android.content.Context
import android.net.Uri
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class NodeLatencyTester(private val context: Context) {
    suspend fun test(rawUri: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val vpnState = VpnRuntime.state.value.state
            if (vpnState != VpnConnectionState.DISCONNECTED && vpnState != VpnConnectionState.ERROR) {
                error("disconnect VPN before url test")
            }

            val config = convertProfile(rawUri)
            val file = File(context.cacheDir, "dot-url-test-${UUID.randomUUID()}.json")
            try {
                file.writeText(config.toString())
                val request = JSONObject()
                    .put("apiVersion", 1)
                    .put("method", "pingBatch")
                    .put(
                        "payload",
                        JSONObject()
                            .put("configs", JSONArray().put(JSONObject().put("configPath", file.absolutePath)))
                            .put("timeout", 5)
                            .put("url", TEST_URL),
                    )
                val response = JSONObject(LibXray.invoke(request.toString()))
                if (!response.optBoolean("success")) {
                    error(response.optString("error", "url test failed"))
                }
                val delay = extractDelay(response.opt("data"))
                when (delay) {
                    10_000L -> error("url test failed")
                    11_000L -> error("url test timeout")
                    in 0L..9_999L -> delay
                    else -> error("libXray returned no latency")
                }
            } finally {
                file.delete()
            }
        }
    }

    private fun convertProfile(rawUri: String): JSONObject {
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", rawUri))
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) {
            error(response.optString("error", "failed to convert VLESS link"))
        }
        val generated = response.optString("data")
        if (generated.isBlank()) error("libXray returned an empty config")
        val config = JSONObject(generated)
        val shareUri = Uri.parse(rawUri)
        config.remove("metrics")
        config.optJSONArray("outbounds")?.let { outbounds ->
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                outbound.remove("sendThrough")
                val stream = outbound.optJSONObject("streamSettings") ?: continue
                if (!stream.optString("security").equals("reality", true)) continue
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
                SERVER_ONLY_REALITY_KEYS.forEach(reality::remove)
                shareUri.getQueryParameter("fp")?.takeIf(String::isNotBlank)?.let { reality.put("fingerprint", it) }
                shareUri.getQueryParameter("sni")?.takeIf(String::isNotBlank)?.let { reality.put("serverName", it) }
                shareUri.getQueryParameter("pbk")?.takeIf(String::isNotBlank)?.let {
                    reality.put("password", it)
                    reality.put("publicKey", it)
                }
                shareUri.getQueryParameter("sid")?.let { reality.put("shortId", it) }
                shareUri.getQueryParameter("spx")?.takeIf(String::isNotBlank)?.let { reality.put("spiderX", it) }
                shareUri.getQueryParameter("pqv")?.takeIf(String::isNotBlank)?.let { reality.put("mldsa65Verify", it) }
            }
        }
        return config
    }

    private fun extractDelay(data: Any?): Long {
        fun fromObject(obj: JSONObject?): Long {
            if (obj == null) return -1L
            if (obj.has("delay")) return obj.optLong("delay", -1L)
            val nested = obj.optJSONObject("data")
            if (nested?.has("delay") == true) return nested.optLong("delay", -1L)
            val results = obj.optJSONArray("results")
            if (results != null && results.length() > 0) return fromObject(results.optJSONObject(0))
            return -1L
        }
        return when (data) {
            is JSONArray -> if (data.length() > 0) fromObject(data.optJSONObject(0)) else -1L
            is JSONObject -> fromObject(data)
            is String -> {
                val text = data.trim()
                runCatching { extractDelay(JSONArray(text)) }.getOrElse {
                    runCatching { extractDelay(JSONObject(text)) }.getOrDefault(-1L)
                }
            }
            else -> -1L
        }
    }

    companion object {
        const val TEST_URL = "http://cp.cloudflare.com/"
        private val SERVER_ONLY_REALITY_KEYS = listOf(
            "target", "dest", "type", "xver", "serverNames", "privateKey",
            "minClientVer", "maxClientVer", "maxTimeDiff", "shortIds", "mldsa65Seed",
            "limitFallbackUpload", "limitFallbackDownload",
        )
    }
}
''')

# ViewModel owns the tester so tests are lifecycle-aware and stay off the UI thread.
replace(
    "app/src/main/java/dev/dotclient/android/ui/MainViewModel.kt",
    "    private val uiPreferences = application.getSharedPreferences(\"dot_ui\", Application.MODE_PRIVATE)\n",
    "    private val uiPreferences = application.getSharedPreferences(\"dot_ui\", Application.MODE_PRIVATE)\n    private val nodeLatencyTester = NodeLatencyTester(application)\n",
)
replace(
    "app/src/main/java/dev/dotclient/android/ui/MainViewModel.kt",
    "    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)\n",
    "    suspend fun testNode(rawUri: String): Result<Long> = nodeLatencyTester.test(rawUri)\n\n    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)\n",
)

# Compose imports for async node test.
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n",
)
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "import kotlin.math.max\n",
    "import kotlinx.coroutines.launch\nimport kotlin.math.max\n",
)

# Wire node tester into each row.
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "                        onConnect = {\n                            viewModel.selectProfile(profile.id)\n                            onConnect()\n                        },\n",
    "                        onConnect = {\n                            viewModel.selectProfile(profile.id)\n                            onConnect()\n                        },\n                        onTest = { viewModel.testNode(profile.rawUri) },\n",
)
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "    onSelect: () -> Unit,\n    onConnect: () -> Unit,\n) {\n    var menuOpen by remember(profile.id) { mutableStateOf(false) }\n",
    "    onSelect: () -> Unit,\n    onConnect: () -> Unit,\n    onTest: suspend () -> Result<Long>,\n) {\n    var menuOpen by remember(profile.id) { mutableStateOf(false) }\n    var latency by remember(profile.id) { mutableStateOf<String?>(null) }\n    val scope = rememberCoroutineScope()\n",
)
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "            Text(\n                \"${profile.host}:${profile.port} · ${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()}\",\n                color = Color(0xFF707070),\n                style = MaterialTheme.typography.labelMedium,\n                maxLines = 1,\n                overflow = TextOverflow.Ellipsis,\n            )\n",
    "            Text(\n                \"${profile.host}:${profile.port} · ${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()}\",\n                color = Color(0xFF707070),\n                style = MaterialTheme.typography.labelMedium,\n                maxLines = 1,\n                overflow = TextOverflow.Ellipsis,\n            )\n            latency?.let { value ->\n                Spacer(Modifier.height(3.dp))\n                Text(value, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelMedium)\n            }\n",
)
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "                DropdownMenuItem(\n                    text = { Text(\"connect\") },\n                    onClick = {\n                        menuOpen = false\n                        onConnect()\n                    },\n                )\n",
    "                DropdownMenuItem(\n                    text = { Text(\"connect\") },\n                    onClick = {\n                        menuOpen = false\n                        onConnect()\n                    },\n                )\n                DropdownMenuItem(\n                    text = { Text(\"url test · Cloudflare\") },\n                    onClick = {\n                        menuOpen = false\n                        latency = \"testing http://cp.cloudflare.com/ …\"\n                        scope.launch {\n                            latency = onTest().fold(\n                                onSuccess = { \"${it} ms · cp.cloudflare.com\" },\n                                onFailure = { it.message ?: \"url test failed\" },\n                            )\n                        }\n                    },\n                )\n",
)

# Launcher icon selector below theme selector.
replace(
    "app/src/main/java/dev/dotclient/android/ui/DotApp.kt",
    "            ThemePicker(\n                selected = state.themeMode,\n                onSelect = viewModel::setTheme,\n            )\n\n            Spacer(Modifier.height(22.dp))\n            Text(\"connection\", style = MaterialTheme.typography.titleLarge)\n",
    "            ThemePicker(\n                selected = state.themeMode,\n                onSelect = viewModel::setTheme,\n            )\n            Spacer(Modifier.height(16.dp))\n            LauncherIconPicker()\n\n            Spacer(Modifier.height(22.dp))\n            Text(\"connection\", style = MaterialTheme.typography.titleLarge)\n",
)

# Add composable before SettingLine.
marker = '''@Composable
private fun SettingLine(name: String, value: String) {'''
insert = r'''@Composable
private fun LauncherIconPicker() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(LauncherIconManager.current(context)) }

    Column {
        Text("app icon", color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherIcon.entries.forEach { icon ->
                val active = icon == selected
                Text(
                    text = icon.label,
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            LauncherIconManager.apply(context, icon)
                            selected = icon
                        }
                        .padding(vertical = 11.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("launcher may refresh the icon with a short delay", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingLine(name: String, value: String) {'''
replace("app/src/main/java/dev/dotclient/android/ui/DotApp.kt", marker, insert)

# Changelog entry.
changelog = root / "CHANGELOG.md"
if changelog.exists():
    text = changelog.read_text(encoding="utf-8")
    anchor = "# Changelog\n"
    entry = '''# Changelog\n\n## 0.0.15\n\n- add per-node URL latency test through libXray using `http://cp.cloudflare.com/`\n- add selectable Android launcher icons: shield, red pixel dot and `dot.` wordmark\n- keep node URL tests disabled while the VPN core is active to avoid libXray process-state collisions\n'''
    if "## 0.0.15" not in text and anchor in text:
        changelog.write_text(text.replace(anchor, entry, 1), encoding="utf-8")
