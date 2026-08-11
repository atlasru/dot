package dev.dotclient.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dotclient.android.ui.DotApp
import dev.dotclient.android.ui.MainViewModel
import dev.dotclient.android.ui.theme.DotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DotTheme {
                DotApp(viewModel<MainViewModel>())
            }
        }
    }
}
