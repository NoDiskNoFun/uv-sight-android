package de.uvsight.core

/**
 * The bow a sight is mounted on, as the sight stores it (setting "bow", firmware 7.7). The sight behaves the
 * same whatever the bow; the app's hints, its wording and the settings it suggests follow the type:
 * 0 compound (sight, release, draw fixed by the cams), 1 recurve with sight (clicker, finger release),
 * 2 barebow (no sight, string walking), 3 traditional (hunting bow, longbow: no sight, no clicker, short hold).
 */
object Bow {
    const val COMPOUND = 0
    const val RECURVE = 1
    const val BAREBOW = 2
    const val TRADITIONAL = 3
    val KEYS = listOf("compound", "recurve", "barebow", "traditional")
    fun key(i: Int) = KEYS.getOrElse(i) { KEYS[0] }
    fun index(k: String?) = KEYS.indexOf(k).coerceAtLeast(0)
    fun label(i: Int): String = when (i) { RECURVE -> tr("Recurve"); BAREBOW -> tr("Barebow"); TRADITIONAL -> tr("Traditional"); else -> tr("Compound") }
    fun describe(i: Int): String = when (i) {
        RECURVE -> tr("Olympic recurve: sight, clicker, finger release. Hints point to the sight, the anchor and the release.")
        BAREBOW -> tr("No sight, string or face walking. Hints point to the crawl and the anchor instead of the sight; the cant tolerance is wider.")
        TRADITIONAL -> tr("Hunting bow, longbow, instinctive: no sight, no clicker, a short hold. Cant is judged by how steady it is, not by its size; the LED signals are off by default.")
        else -> tr("Sight with a scale, peep and release; the draw is fixed by the cams. Hints point to the sight and the release; the cant tolerance is tight.")
    }
    /** The bow has a sight whose setting the hints can talk about. */
    fun hasSight(i: Int) = i == COMPOUND || i == RECURVE

    /** One suggested sight setting: a settings-table key with its value, or a signal switch. */
    data class Suggestion(val key: String, val value: Double, val label: String)

    /** The settings the app suggests for a bow. "cant_signal", "led_mode" and "range_signal" are switches (1 on, 0 off), the rest settings-table keys. */
    fun suggestions(i: Int): List<Suggestion> {
        val (tol, full) = when (i) { RECURVE -> 2.0 to 8.0; BAREBOW -> 4.0 to 12.0; TRADITIONAL -> 5.0 to 15.0; else -> 1.5 to 6.0 }
        val out = arrayListOf(Suggestion("level_tol", tol, tr("Cant tolerance")), Suggestion("tilt_full", full, tr("Blinking range up to")))
        out.add(Suggestion("cant_signal", if (i == TRADITIONAL) 0.0 else 1.0, tr("Blink when canted")))
        if (!hasSight(i)) { out.add(Suggestion("led_mode", 0.0, tr("LED"))); out.add(Suggestion("range_signal", 0.0, tr("Distance warning"))) }
        return out
    }

    /** Thresholds of the hints per bow. */
    data class Profile(
        val cantTol: Double,          // mean cant above this is reported (sight bows); traditional judges steadiness instead
        val cantAsSteadiness: Boolean,
        val hardReleaseMin: Double,   // °/s floor for a "hard release" (bows without stabilisers turn more by nature)
        val sinkThreshold: Double,    // aim drop (deg) before the release that counts as sinking; the short hold of a snap shooter is no sinking
    )
    fun profile(i: Int, cantTol: Double) = when (i) {
        RECURVE -> Profile(cantTol, false, 150.0, -0.4)
        BAREBOW -> Profile(cantTol, false, 250.0, -0.5)
        TRADITIONAL -> Profile(cantTol, true, 350.0, -0.8)
        else -> Profile(cantTol, false, 150.0, -0.4)
    }
}
