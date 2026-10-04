package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test fun packParsingAndLookup() {
        val pack = """
            # name = Deutsch
            Connecting = Verbinden
            End {end}, arrow {arrow} = Passe {end}, Pfeil {arrow}
            Shot matching accuracy = Genauigkeit der Schusszuordnung
            Two lines\nhere = Zwei Zeilen\nhier
            Untranslated =
        """.trimIndent()
        assertEquals("Deutsch", Lang.nameOf(pack))
        Lang.set("de", Lang.parse(pack))
        try {
            assertEquals("Verbinden", tr("Connecting"))
            assertEquals("Passe 4, Pfeil 3", tr("End {end}, arrow {arrow}", "end" to 4, "arrow" to 3))
            assertEquals("Genauigkeit der Schusszuordnung ", tr("Shot matching accuracy "))     // the key's own spacing is kept
            assertEquals("Zwei Zeilen\nhier", tr("Two lines\nhere"))
            assertEquals("Untranslated", tr("Untranslated"))                                     // empty right side: English
            assertEquals("Not in the pack 7", tr("Not in the pack {n}", "n" to 7))
        } finally { Lang.set("en", emptyMap()) }
    }
}
