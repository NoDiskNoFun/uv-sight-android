package de.uvsight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Notification-relevant events and the live range signal, with a scripted transport. */
class EventsTest {
    private class FakeTransport : Transport {
        val sent = ArrayList<String>()
        override var connected = true
        override fun send(cmd: String) { sent.add(cmd) }
        override suspend fun ensureConnected(timeoutMs: Long) = true
        override fun disconnect() { connected = false }
    }

    @Test fun traceArrivesInParts() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ctl = SightController(scope, MemoryStore(), FakeTransport(), object : UiSink { override fun toast(text: String, error: Boolean) {} })
        try {
            runBlocking {
                withContext(dispatcher) { ctl.onLinkUp() }
                delay(100)
                withContext(dispatcher) {
                    ctl.onLine("""{"t":"hello","proto":23,"fw":"7.0","name":"UV-Sight","imu":true,"log":true,"id":"ABCDEF0123456789","sname":"Hoyt"}""")
                    // three parts of a 7-sample trace; a stale part of another shot in between is dropped
                    ctl.onLine("""{"t":"trace","end":3,"i":1,"n":7,"k":0,"ms":[1891,1861,1831],"pitch":[120,118,117],"cant":[-30,-28,-25]}""")
                    assertEquals(null, ctl.state.value.trace)
                    ctl.onLine("""{"t":"trace","end":3,"i":1,"n":7,"k":3,"ms":[1801,1771,1741],"pitch":[116,116,115],"cant":[-20,-18,-15]}""")
                    ctl.onLine("""{"t":"trace","end":3,"i":1,"n":7,"k":6,"ms":[1711],"pitch":[114],"cant":[-12]}""")
                }
                delay(100)
                val tr = ctl.state.value.trace!!
                assertEquals(7, tr.ms.size); assertEquals(1891, tr.ms[0]); assertEquals(1711, tr.ms[6])
                assertEquals(1.14, tr.pitch[6], 1e-9); assertEquals(-0.12, tr.cant[6], 1e-9)
                assertEquals(3, tr.end); assertEquals(1, tr.i)
                // an empty trace (shot without angle) is one part with n = 0
                withContext(dispatcher) { ctl.clearTrace(); ctl.onLine("""{"t":"trace","end":3,"i":0,"n":0,"k":0,"ms":[],"pitch":[],"cant":[]}""") }
                delay(100)
                assertEquals(0, ctl.state.value.trace!!.ms.size)
            }
        } finally { scope.cancel(); dispatcher.close() }
    }

    @Test fun lockedSightTakesThePassword() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = MemoryStore()
        val transport = FakeTransport()
        val ctl = SightController(scope, store, transport, object : UiSink { override fun toast(text: String, error: Boolean) {} })
        try {
            runBlocking {
                withContext(dispatcher) { ctl.setGroup(GroupPrefs(role = "user", name = "Lena", password = "geheim42")) }
                assertEquals(16, ctl.state.value.group.id.length)
                withContext(dispatcher) { ctl.onLinkUp() }
                delay(100)
                assertTrue(transport.sent.last().startsWith("app on ${ctl.state.value.group.id} Lena"), "names itself: ${transport.sent.last()}")
                withContext(dispatcher) { ctl.onLine("""{"t":"hello","proto":23,"fw":"7.0","name":"UV-Sight","imu":true,"log":true,"id":"ABCDEF0123456789","sname":"Club 3","led":true,"pw":true,"role":"locked","pending":false,"primary":true}""") }
                delay(200)
                assertEquals("auth geheim42", transport.sent.last())
                assertEquals("locked", ctl.state.value.role)
                withContext(dispatcher) { ctl.onLine("""{"t":"auth","ok":true,"role":"user","pending":false}""") }
                delay(300)
                assertEquals(ConnState.CONNECTED, ctl.state.value.conn)
                assertEquals("user", ctl.state.value.role)
                assertEquals(false, ctl.state.value.needsPassword)
                // a session of this archer, as the sight lists it
                withContext(dispatcher) { ctl.onLine("""{"t":"slot","seq":5,"epoch":7,"id":3,"start":null,"ago":1,"ends":2,"shots":6,"scored":6,"avg":8.5,"x":1,"min":10,"invalidEnds":0,"invalidArrows":0,"mismatch":false,"manual":false,"owner":"${ctl.state.value.group.id}","ownerName":"Lena"}""") }
                delay(100)
                val r = ctl.state.value.sessions.first { it.key == "7-3" }
                assertEquals("Lena", r.ownerName)
            }
        } finally { scope.cancel(); dispatcher.close() }
    }

    @Test fun rangeEventAndAlerts() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = MemoryStore()
        val transport = FakeTransport()
        val ctl = SightController(scope, store, transport, object : UiSink { override fun toast(text: String, error: Boolean) {} })
        val events = ArrayList<CoreEvent>()
        scope.launch { ctl.events.collect { events.add(it) } }
        try {
            runBlocking {
                withContext(dispatcher) { ctl.onLinkUp() }
                delay(100)                                   // "app on" goes out, the controller now waits for hello
                withContext(dispatcher) { ctl.onLine("""{"t":"hello","proto":23,"fw":"7.0","name":"UV-Sight","imu":true,"log":true,"id":"ABCDEF0123456789","sname":"Hoyt"}""") }
                delay(200)
                assertEquals(ConnState.CONNECTED, ctl.state.value.conn)
                withContext(dispatcher) {
                    ctl.onLine("""{"t":"status","light":500,"dark":true,"vbat":3.45,"pct":9,"chg":"no USB","led":"off","duty":0,"bright":61,"mode":"auto","lowbat":false,"tilt":null,"session":false,"awake":"","rwarn":false,"lastShotG":null,"lastShotClip":false}""")
                    ctl.onLine("""{"t":"status","light":500,"dark":true,"vbat":3.45,"pct":9,"chg":"no USB","led":"off","duty":0,"bright":61,"mode":"auto","lowbat":false,"tilt":null,"session":false,"awake":"","rwarn":false,"lastShotG":null,"lastShotClip":false}""")
                    ctl.onLine("""{"t":"event","e":"range","warn":"low","ok":false}""")
                }
                delay(100)
                assertEquals(RangeLive("low", false), ctl.state.value.rangeLive)
                withContext(dispatcher) {
                    ctl.onLine("""{"t":"event","e":"range","warn":"","ok":true}""")
                    ctl.onLine("""{"t":"event","e":"charge","state":"full"}""")
                    ctl.onLine("""{"t":"sessionEnd","manual":false,"stored":true,"epoch":7,"id":3,"seq":9}""")
                    ctl.onLine("""{"t":"slot","seq":9,"epoch":7,"id":3,"start":null,"ago":0,"ends":4,"shots":12,"scored":12,"avg":8.25,"x":2,"min":40,"invalidEnds":0,"invalidArrows":0,"mismatch":false,"manual":false}""")
                }
                delay(200)
                assertEquals(RangeLive("", true), ctl.state.value.rangeLive)
                assertEquals(1, events.count { it is CoreEvent.LowBattery }, "low battery reported once per connection")
                assertEquals(1, events.count { it is CoreEvent.ChargeFull })
                val ended = events.filterIsInstance<CoreEvent.SessionAutoEnded>().single()
                assertEquals(4, ended.ends); assertEquals(8.25, ended.avg, 1e-9)
                assertEquals(1, ctl.state.value.sessions.size)
                withContext(dispatcher) { ctl.onLinkDown() }
                delay(100)
                assertEquals(RangeLive(), ctl.state.value.rangeLive)
                assertTrue(transport.sent.contains("app on"))
            }
        } finally { dispatcher.close() }
    }
}
