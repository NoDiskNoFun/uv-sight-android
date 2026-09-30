package de.uvsight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uvsight.core.SightController
import de.uvsight.core.UiSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class Toast(val text: String, val error: Boolean)

class SightViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = PrefsStore(app)
    private val _toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val toasts: SharedFlow<Toast> = _toasts
    val ble = SightBle(app, viewModelScope, prefs)
    val controller = SightController(viewModelScope, prefs, ble, object : UiSink {
        override fun toast(text: String, error: Boolean) { _toasts.tryEmit(Toast(text, error)) }
    })
    val state get() = controller.state

    init {
        ble.controller = controller
        ble.onProblem = { viewModelScope.launch { _toasts.emit(Toast(it, true)) } }
    }

    fun toast(text: String, error: Boolean = false) { _toasts.tryEmit(Toast(text, error)) }

    override fun onCleared() { ble.disconnect() }
}
