package de.uvsight.app

import android.content.Context
import de.uvsight.core.Lang
import java.util.Locale

/** Language packs live in assets/lang/lang_<code>.txt and ship with the APK. */
object Language {
    private const val DIR = "lang"

    /** code to name of every pack in the APK, English first. */
    fun available(context: Context): List<Pair<String, String>> {
        val files = runCatching { context.assets.list(DIR)?.toList() }.getOrNull() ?: emptyList()
        return files.filter { it.startsWith("lang_") && it.endsWith(".txt") }.mapNotNull { f ->
            val code = f.removePrefix("lang_").removeSuffix(".txt")
            val name = runCatching { context.assets.open("$DIR/$f").bufferedReader().use { it.readText() } }.getOrNull()?.let { Lang.nameOf(it) } ?: code
            code to name
        }.sortedWith(compareBy({ it.first != "en" }, { it.second }))
    }

    /** Applies a pack: "" = the phone's language if there is a pack for it, else English. */
    fun apply(context: Context, setting: String) {
        val wanted = setting.ifEmpty { Locale.getDefault().language }
        val codes = available(context).map { it.first }
        val code = if (wanted in codes) wanted else "en"
        val text = runCatching { context.assets.open("$DIR/lang_$code.txt").bufferedReader().use { it.readText() } }.getOrNull()
        Lang.set(code, if (code == "en" || text == null) emptyMap() else Lang.parse(text))
    }
}
