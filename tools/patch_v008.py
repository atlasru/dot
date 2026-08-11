from pathlib import Path

# DotUiState
p = Path('app/src/main/java/dev/dotclient/android/ui/DotUiState.kt')
s = p.read_text()
s = s.replace('import dev.dotclient.android.core.model.VlessProfile\n', 'import dev.dotclient.android.core.model.VlessProfile\nimport dev.dotclient.android.ui.theme.DotThemeMode\n')
s = s.replace('    val message: String? = null,\n', '    val message: String? = null,\n    val themeMode: DotThemeMode = DotThemeMode.AMOLED,\n')
p.write_text(s)

# MainViewModel
p = Path('app/src/main/java/dev/dotclient/android/ui/MainViewModel.kt')
s = p.read_text()
s = s.replace('import dev.dotclient.android.vpn.VpnRuntime\n', 'import dev.dotclient.android.vpn.VpnRuntime\nimport dev.dotclient.android.ui.theme.DotThemeMode\n')
s = s.replace('    private val subscriptionStore = SubscriptionStore(application)\n', '    private val subscriptionStore = SubscriptionStore(application)\n    private val uiPreferences = application.getSharedPreferences("dot_ui", Application.MODE_PRIVATE)\n')
s = s.replace('            selectedSubscriptionId = stored.selectedSubscriptionId,\n        )', '            selectedSubscriptionId = stored.selectedSubscriptionId,\n            themeMode = DotThemeMode.fromStorage(uiPreferences.getString("theme", null)),\n        )')
insert = '''\n    fun setTheme(theme: DotThemeMode) {\n        mutableState.update { it.copy(themeMode = theme) }\n        uiPreferences.edit().putString("theme", theme.name).apply()\n    }\n'''
s = s.replace('\n    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)\n', insert + '\n    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)\n')
p.write_text(s)

# Theme
Path('app/src/main/java/dev/dotclient/android/ui/theme/DotTheme.kt').write_text('''package dev.dotclient.android.ui.theme\n\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Typography\nimport androidx.compose.material3.darkColorScheme\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.text.TextStyle\nimport androidx.compose.ui.text.font.FontFamily\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.unit.sp\n\nenum class DotThemeMode(val label: String) {\n    AMOLED("AMOLED"),\n    GRAPHITE("Graphite"),\n    MATRIX("Matrix");\n\n    companion object {\n        fun fromStorage(value: String?): DotThemeMode = entries.firstOrNull { it.name == value } ?: AMOLED\n    }\n}\n\nprivate val AmoledColors = darkColorScheme(\n    primary = Color.White, onPrimary = Color.Black, background = Color.Black, onBackground = Color.White,\n    surface = Color.Black, onSurface = Color.White, surfaceVariant = Color(0xFF101010),\n    onSurfaceVariant = Color(0xFFB8B8B8), outline = Color(0xFF303030), error = Color(0xFFFF3B30),\n)\n\nprivate val GraphiteColors = darkColorScheme(\n    primary = Color(0xFFE8E8E8), onPrimary = Color(0xFF151515), background = Color(0xFF121212),\n    onBackground = Color(0xFFF0F0F0), surface = Color(0xFF181818), onSurface = Color(0xFFF0F0F0),\n    surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFB8B8B8), outline = Color(0xFF444444),\n    error = Color(0xFFFF5A52),\n)\n\nprivate val MatrixColors = darkColorScheme(\n    primary = Color(0xFF78FF78), onPrimary = Color.Black, background = Color.Black, onBackground = Color(0xFFD8FFD8),\n    surface = Color(0xFF020A02), onSurface = Color(0xFFD8FFD8), surfaceVariant = Color(0xFF071507),\n    onSurfaceVariant = Color(0xFF86B886), outline = Color(0xFF245A24), error = Color(0xFFFF5252),\n)\n\nprivate val CourierLike = FontFamily.Monospace\nprivate val DotTypography = Typography(\n    displayLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 56.sp, letterSpacing = (-2).sp),\n    headlineLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 28.sp),\n    titleLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 20.sp),\n    bodyLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 16.sp),\n    bodyMedium = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 14.sp),\n    labelLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp),\n    labelMedium = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.8.sp),\n)\n\n@Composable\nfun DotTheme(themeMode: DotThemeMode = DotThemeMode.AMOLED, content: @Composable () -> Unit) {\n    val colors = when (themeMode) {\n        DotThemeMode.AMOLED -> AmoledColors\n        DotThemeMode.GRAPHITE -> GraphiteColors\n        DotThemeMode.MATRIX -> MatrixColors\n    }\n    MaterialTheme(colorScheme = colors, typography = DotTypography, content = content)\n}\n''')

# MainActivity observes theme
Path('app/src/main/java/dev/dotclient/android/MainActivity.kt').write_text('''package dev.dotclient.android\n\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\nimport androidx.activity.enableEdgeToEdge\nimport androidx.activity.viewModels\nimport androidx.lifecycle.compose.collectAsStateWithLifecycle\nimport dev.dotclient.android.ui.DotApp\nimport dev.dotclient.android.ui.MainViewModel\nimport dev.dotclient.android.ui.theme.DotTheme\n\nclass MainActivity : ComponentActivity() {\n    private val mainViewModel: MainViewModel by viewModels()\n\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()\n        setContent {\n            val state = mainViewModel.state.collectAsStateWithLifecycle().value\n            DotTheme(themeMode = state.themeMode) {\n                DotApp(mainViewModel)\n            }\n        }\n    }\n}\n''')

# DotApp targeted edits
p = Path('app/src/main/java/dev/dotclient/android/ui/DotApp.kt')
s = p.read_text()
s = s.replace('import androidx.activity.compose.rememberLauncherForActivityResult\n', 'import androidx.activity.compose.BackHandler\nimport androidx.activity.compose.rememberLauncherForActivityResult\n')
s = s.replace('import dev.dotclient.android.vpn.VpnConnectionState\n', 'import dev.dotclient.android.vpn.VpnConnectionState\nimport dev.dotclient.android.ui.theme.DotThemeMode\n')
# Back only for nodes at app level; settings handles editor itself
needle = '    var screen by remember { mutableStateOf(Screen.HOME) }\n\n'
s = s.replace(needle, needle + '    BackHandler(enabled = screen == Screen.NODES) { screen = Screen.HOME }\n\n')
# Remove bottom bar
s = s.replace('''    Scaffold(\n        containerColor = Color.Black,\n        bottomBar = {\n            if (screen != Screen.SETTINGS) {\n                DotBottomBar(screen = screen, onScreen = { screen = it })\n            }\n        },\n    ) { padding ->''', '''    Scaffold(\n        containerColor = MaterialTheme.colorScheme.background,\n    ) { padding ->''')
# Nodes close callback
s = s.replace('''                onAddSubscription = { screen = Screen.SETTINGS },\n                onConnect = {''', '''                onAddSubscription = { screen = Screen.SETTINGS },\n                onClose = { screen = Screen.HOME },\n                onConnect = {''')
s = s.replace('''    onAddSubscription: () -> Unit,\n    onConnect: () -> Unit,''', '''    onAddSubscription: () -> Unit,\n    onClose: () -> Unit,\n    onConnect: () -> Unit,''', 1)
# nodes heading close x
s = s.replace('''            Text("nodes.", style = MaterialTheme.typography.headlineLarge)\n\n            Box {''', '''            Row(verticalAlignment = Alignment.CenterVertically) {\n                Text("nodes.", style = MaterialTheme.typography.headlineLarge)\n                Spacer(Modifier.size(10.dp))\n                Text(\n                    "×",\n                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp),\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    style = MaterialTheme.typography.titleLarge,\n                )\n            }\n\n            Box {''')
# Settings back behavior including editor
needle = '''    fun openEditEditor(subscription: Subscription) {\n        editingId = subscription.id\n        draftName = subscription.name\n        draftUrl = subscription.url\n        editorOpen = true\n    }\n\n'''
s = s.replace(needle, needle + '''    BackHandler {\n        if (editorOpen) editorOpen = false else onBack()\n    }\n\n''')
# Add appearance before connection
needle = '''            Spacer(Modifier.height(22.dp))\n            Text("connection", style = MaterialTheme.typography.titleLarge)'''
appearance = '''            Spacer(Modifier.height(22.dp))\n            Text("appearance", style = MaterialTheme.typography.titleLarge)\n            Spacer(Modifier.height(10.dp))\n            ThemePicker(\n                selected = state.themeMode,\n                onSelect = viewModel::setTheme,\n            )\n\n            Spacer(Modifier.height(22.dp))\n            Text("connection", style = MaterialTheme.typography.titleLarge)'''
s = s.replace(needle, appearance)
# clean advanced lines
s = s.replace('            SettingLine("core", "libXray · next")\n            SettingLine("theme", "AMOLED")\n            SettingLine("type", "monospace / courier-like")\n            SettingLine("telemetry", "off")\n', '            SettingLine("core", "libXray")\n            SettingLine("type", "monospace / courier-like")\n')
# Main notable colors use theme
s = s.replace('containerColor = Color.White,\n                contentColor = Color.Black,', 'containerColor = MaterialTheme.colorScheme.primary,\n                contentColor = MaterialTheme.colorScheme.onPrimary,', 1)
s = s.replace('modifier = Modifier.size(96.dp).border(1.dp, Color.White, CircleShape)', 'modifier = Modifier.size(96.dp).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)', 1)
s = s.replace('color = Color.White,\n                )', 'color = MaterialTheme.colorScheme.primary,\n                )', 1)
s = s.replace('if (state.vpnConnected) Color.White else Color(0xFF7A7A7A)', 'if (state.vpnConnected) MaterialTheme.colorScheme.primary else Color(0xFF7A7A7A)', 1)
# remove bottom bar function
start = s.find('\n@Composable\nprivate fun DotBottomBar(')
if start != -1:
    end = s.find('\nprivate fun updatedAgo', start)
    s = s[:start] + s[end:]
# Insert ThemePicker before SettingLine
marker = '\n@Composable\nprivate fun SettingLine(name: String, value: String) {'
theme_picker = '''\n@Composable\nprivate fun ThemePicker(selected: DotThemeMode, onSelect: (DotThemeMode) -> Unit) {\n    Row(\n        modifier = Modifier.fillMaxWidth(),\n        horizontalArrangement = Arrangement.spacedBy(8.dp),\n    ) {\n        DotThemeMode.entries.forEach { theme ->\n            val active = theme == selected\n            Text(\n                text = theme.label,\n                modifier = Modifier\n                    .weight(1f)\n                    .border(\n                        1.dp,\n                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,\n                        RoundedCornerShape(4.dp),\n                    )\n                    .background(\n                        if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,\n                        RoundedCornerShape(4.dp),\n                    )\n                    .clickable { onSelect(theme) }\n                    .padding(vertical = 11.dp),\n                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,\n                style = MaterialTheme.typography.labelMedium,\n                textAlign = androidx.compose.ui.text.style.TextAlign.Center,\n            )\n        }\n    }\n}\n'''
s = s.replace(marker, theme_picker + marker)
p.write_text(s)

# Version
p = Path('app/build.gradle.kts')
s = p.read_text().replace('versionCode = 7', 'versionCode = 8').replace('versionName = "0.0.7"', 'versionName = "0.0.8"')
p.write_text(s)
