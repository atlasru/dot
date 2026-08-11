package dev.dotclient.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotclient.android.ui.DotApp
import dev.dotclient.android.ui.MainViewModel
import dev.dotclient.android.ui.theme.DotTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = mainViewModel.state.collectAsStateWithLifecycle().value
            DotTheme(themeMode = state.themeMode) {
                DotApp(mainViewModel)
            }
        }
    }
}
