package de.uvsight.core

/** String key/value storage (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** The Bluetooth link, provided by the platform layer. */
interface Transport {
    val connected: Boolean
    /** Queue one command line (without newline). */
    fun send(cmd: String)
    /**
     * Make sure the sight is connected: reconnect to the remembered device, or run the
     * device chooser the first time. Returns true when connected.
     */
    suspend fun ensureConnected(timeoutMs: Long): Boolean
    /** Drop the link on purpose (e.g. after the sight announced it sleeps). */
    fun disconnect()
}

/** Short user feedback outside the state (snackbar / toast). */
interface UiSink {
    fun toast(text: String, error: Boolean = false)
}

interface Clock { fun now(): Long }
object SystemClock : Clock { override fun now() = System.currentTimeMillis() }
