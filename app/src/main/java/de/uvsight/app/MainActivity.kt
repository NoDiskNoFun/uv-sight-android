package de.uvsight.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.uvsight.app.ui.UvSightApp
import de.uvsight.app.ui.UvSightTheme

class MainActivity : ComponentActivity() {
    private val vm: SightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UvSightTheme { UvSightApp(vm) } }
    }

    override fun onStart() { super.onStart(); vm.controller.onAppVisible(true) }
    override fun onStop() { super.onStop(); vm.controller.onAppVisible(false) }
}
