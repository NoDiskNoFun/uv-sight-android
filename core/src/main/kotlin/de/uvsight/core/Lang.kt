package de.uvsight.core

/**
 * Translations. A language pack is a plain text file, one line per text:
 *
 *     # name = Deutsch
 *     Connecting = Verbinden
 *     End {end}, arrow {arrow} = Passe {end}, Pfeil {arrow}
 *
 * The left side is the English text as it appears in the code (the key), the right side the
 * translation. Lines starting with # are comments, {name} are placeholders that stay as they
 * are, \n is a line break. An empty right side, or a missing line, falls back to English.
 */
object Lang {
    @Volatile var code: String = "en"
    @Volatile private var table: Map<String, String> = emptyMap()

    fun set(code: String, table: Map<String, String>) { this.code = code; this.table = table }

    fun tr(key: String, vararg args: Pair<String, Any?>): String {
        var s = table[key]?.takeIf { it.isNotEmpty() } ?: key
        for ((k, v) in args) s = s.replace("{$k}", v.toString())
        return s
    }

    /** Parses a language pack. */
    fun parse(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) continue
            val i = line.indexOf(" = ")
            if (i <= 0) continue
            val k = unescape(line.substring(0, i).trim()); val v = unescape(line.substring(i + 3).trim())
            if (v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** The pack's own name from its "# name = ..." line. */
    fun nameOf(text: String): String? = text.lineSequence().firstOrNull { it.trim().startsWith("# name") }?.substringAfter("=", "")?.trim()?.takeIf { it.isNotEmpty() }

    private fun unescape(s: String) = s.replace("\\n", "\n")
}

/** The translated text for an English key, with {placeholders} filled in. */
fun tr(key: String, vararg args: Pair<String, Any?>): String = Lang.tr(key, *args)
