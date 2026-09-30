package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test fun parsesStatus() {
        val m = Msg.parse("""{"t":"status","light":500,"dark":true,"vbat":3.90,"pct":64,"chg":"no USB","led":"on","duty":1763,"bright":61,"mode":"auto","lowbat":false,"tilt":null,"session":false,"awake":"","rwarn":false,"lastShotG":null,"lastShotClip":false}""")
        assertNotNull(m)
        val st = m.toStatus()
        assertEquals(64, st.pct); assertEquals(3.9, st.vbat, 1e-9); assertNull(st.tilt); assertTrue(st.dark); assertNull(st.lastShotG)
    }
    @Test fun parsesSessionKey() {
        val m = Msg.parse("""{"t":"session","counter":true,"active":true,"epoch":1936311911,"nextId":1,"dist":0,"distSrc":"none","end":1,"endShots":0,"ends":0,"invalidEnds":0,"shots":0,"scored":0,"sum":0,"x":0,"avg":0.00,"min":0,"pending":false}""")!!
        assertEquals("1936311911-1", m.toSession().key)
    }
    @Test fun parsesEndRecScores() {
        val m = Msg.parse("""{"t":"endrec","seq":1,"epoch":5,"id":1,"n":1,"valid":true,"arrows":3,"sum":27,"x":0,"ang":1.25,"angSd":0.10,"angN":3,"scores":"10 9 8"}""")!!
        val e = m.toEndRec()
        assertEquals("10 9 8", e.scores); assertEquals(1.25, e.ang!!, 1e-9); assertNull(e.dist)
    }
    @Test fun ignoresTextLines() { assertNull(Msg.parse("UV-Sight connected. 'help' lists all commands.")) }
    @Test fun logEndCount() { assertEquals(2, Msg.parse("""{"t":"logEnd","last":2,"n":2}""")!!.int("n")) }
}
