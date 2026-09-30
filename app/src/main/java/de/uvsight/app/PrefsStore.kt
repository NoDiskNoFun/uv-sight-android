package de.uvsight.app

import android.content.Context
import android.content.SharedPreferences
import de.uvsight.core.KeyValueStore

/** The phone's storage for the archive and the app's small settings. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("uvsight", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
