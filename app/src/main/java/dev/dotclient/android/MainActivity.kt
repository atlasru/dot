package dev.dotclient.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotclient.android.ui.DotApp
import dev.dotclient.android.ui.MainViewModel
import dev.dotclient.android.ui.theme.DotTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themes = remember { mainViewModel.state.map { it.themeMode }.distinctUntilChanged() }
            val theme by themes.collectAsStateWithLifecycle(mainViewModel.state.value.themeMode)
            DotTheme(themeMode = theme) {
                DotApp(mainViewModel)
            }
        }
    }
}
