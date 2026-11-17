package io.github.fermframe.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var openMac by mutableStateOf<String?>(null)   // set when an alert is tapped

    override fun onCreate(savedInstanceState: Bundle?) {
        // adb shell am start -n io.github.fermframe.app/.MainActivity --ez demo true   (made-up FermFrames)
        if (intent?.getBooleanExtra("demo", false) == true) Demo.on = true
        openMac = intent?.getStringExtra("mac")
        BuildConfigVersion.name = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Alerts.schedule(this)
        setContent {
            FermFrameTheme {
                val m: AppModel = viewModel()
                LaunchedEffect(Unit) {
                    m.refresh(scan = m.clocks.isEmpty())
                    if (!Demo.on) m.checkForUpdate()
                    while (true) { delay(60_000); m.refresh() }   // keep checking while the app is open
                }
                LaunchedEffect(openMac) { openMac?.let { m.showAlerts = false; m.selected = it; openMac = null } }
                val c = m.current
                when {
                    m.showAlerts -> { BackHandler { m.showAlerts = false }; AlertsScreen(m) { m.showAlerts = false } }
                    m.showSetup -> { BackHandler { m.showSetup = false }; SetupScreen(m) { m.showSetup = false } }
                    c == null -> HomeScreen(m)
                    else -> { BackHandler { m.selected = null }; DetailScreen(m, c) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("mac")?.let { openMac = it }
    }
}
