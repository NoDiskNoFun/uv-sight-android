package de.uvsight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.SharedFlow

/** Thin view model over the process-wide runtime (which outlives the Activity). */
class SightViewModel(app: Application) : AndroidViewModel(app) {
    private val rt = SightRuntime.get(app)
    val toasts: SharedFlow<Toast> = rt.toasts
    val ble = rt.ble
    val controller = rt.controller
    val state get() = controller.state
    fun toast(text: String, error: Boolean = false) = rt.toast(text, error)
    /** Switch the language pack ("" = phone language); the state change re-renders everything. */
    fun setLanguage(code: String) { Language.apply(getApplication(), code); controller.setLanguage(code) }
}
