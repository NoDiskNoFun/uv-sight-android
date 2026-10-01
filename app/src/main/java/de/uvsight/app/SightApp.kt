package de.uvsight.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import de.uvsight.core.ConnState
import de.uvsight.core.SightController
import de.uvsight.core.UiSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class Toast(val text: String, val error: Boolean)

class SightApp : Application() {
    override fun onCreate() { super.onCreate(); SightRuntime.get(this) }
}

/**
 * Process-wide runtime: the link to the sight, the controller and the helpers that turn its
 * state into notifications and vibration. Lives beyond the Activity so the session can go on
 * with the phone in the pocket (through [SightService]).
 */
class SightRuntime private constructor(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = PrefsStore(app)
    val toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val ble = SightBle(app, scope, prefs)
    val controller = SightController(scope, prefs, ble, object : UiSink {
        override fun toast(text: String, error: Boolean) { toasts.tryEmit(Toast(text, error)) }
    })
    val haptics = Haptics(app, controller, scope)
    val alerts = Alerts(app, controller, scope)

    init {
        ble.controller = controller
        ble.onProblem = { toasts.tryEmit(Toast(it, true)) }
        // The foreground service runs while a link is wanted (connecting, connected or
        // reconnecting) and the session notification is enabled
        scope.launch {
            controller.state.map { (it.conn != ConnState.OFF || it.autoReconnect) && it.notif.session && !it.photoOnly }.distinctUntilChanged().collect { wanted ->
                val intent = Intent(app, SightService::class.java)
                if (wanted) runCatching { ContextCompat.startForegroundService(app, intent) }
                else runCatching { app.stopService(intent) }
            }
        }
    }

    fun toast(text: String, error: Boolean = false) { toasts.tryEmit(Toast(text, error)) }

    companion object {
        @Volatile private var instance: SightRuntime? = null
        fun get(context: Context): SightRuntime = instance ?: synchronized(this) {
            instance ?: SightRuntime(context.applicationContext as Application).also { instance = it }
        }
    }
}
