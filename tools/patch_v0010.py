from pathlib import Path

p = Path('app/build.gradle.kts')
s = p.read_text()
s = s.replace('versionCode = 9\n        versionName = "0.0.9"', 'versionCode = 10\n        versionName = "0.0.10"')
p.write_text(s)

p = Path('app/src/main/java/dev/dotclient/android/ui/DotApp.kt')
s = p.read_text()

s = s.replace('import android.content.pm.PackageManager\n', 'import android.content.Intent\nimport android.content.pm.PackageManager\n')
s = s.replace('import android.net.VpnService\n', 'import android.net.Uri\nimport android.net.VpnService\n')
s = s.replace('private enum class Screen { HOME, NODES, SETTINGS }', 'private enum class Screen { HOME, NODES, SETTINGS, ABOUT }')

s = s.replace(
'''            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                onBack = { screen = Screen.HOME },
                modifier = Modifier.padding(padding),
            )
''',
'''            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                onBack = { screen = Screen.HOME },
                onAbout = { screen = Screen.ABOUT },
                modifier = Modifier.padding(padding),
            )

            Screen.ABOUT -> AboutScreen(
                onBack = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )
''')

s = s.replace(
'''private fun SettingsScreen(
    state: DotUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {''',
'''private fun SettingsScreen(
    state: DotUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {''')

s = s.replace(
'''            SettingLine("core", "libXray")
            SettingLine("type", "monospace / courier-like")

            Spacer(Modifier.weight(1f))
''',
'''            SettingLine("core", "libXray")
            SettingLine("type", "monospace / courier-like")

            Spacer(Modifier.height(18.dp))
            Text("about", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAbout)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("dot.", color = Color(0xFFB0B0B0))
                Text("v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}  ›", color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.weight(1f))
''')

anchor = '''@Composable
private fun SubscriptionRow(
'''
about = '''@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text("about.", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(Modifier.height(42.dp))

        Text("dot.", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "minimal VLESS client for Android",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(34.dp))
        AboutValue("version", BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        AboutValue("core", "libXray v26.7.28")
        AboutValue("protocol", "VLESS / REALITY")
        AboutValue("android", "API 26+")

        Spacer(Modifier.height(28.dp))
        Text("project", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        AboutLink("github", "atlasru/dot") {
            openUrl("https://github.com/atlasru/dot")
        }
        AboutLink("license", "open source") {
            openUrl("https://github.com/atlasru/dot/blob/main/LICENSE")
        }

        Spacer(Modifier.weight(1f))

        Text(
            "built around Xray-core · no accounts · no analytics",
            color = Color(0xFF666666),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "dot. / ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}",
            color = Color(0xFF444444),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AboutValue(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = Color(0xFF8A8A8A))
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutLink(name: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color(0xFF8A8A8A))
        Text("$value  ↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

'''
if anchor not in s:
    raise SystemExit('SubscriptionRow anchor missing')
s = s.replace(anchor, about + anchor, 1)

p.write_text(s)
