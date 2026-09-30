package de.uvsight.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.uvsight.app.ui.UvSightApp
import de.uvsight.app.ui.UvSightTheme
import de.uvsight.core.View

class MainActivity : ComponentActivity() {
    private val vm: SightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UvSightTheme { UvSightApp(vm) } }
        openView(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openView(intent)
    }

    /** A notification was tapped: open the tab it names. */
    private fun openView(intent: Intent?) {
        when (intent?.getStringExtra("view")) {
            "training" -> vm.controller.setView(View.TRAINING)
            "history" -> vm.controller.setView(View.HISTORY)
            "status" -> vm.controller.setView(View.STATUS)
        }
    }

    override fun onStart() { super.onStart(); vm.controller.onAppVisible(true) }
    override fun onStop() { super.onStop(); vm.controller.onAppVisible(false) }
}
