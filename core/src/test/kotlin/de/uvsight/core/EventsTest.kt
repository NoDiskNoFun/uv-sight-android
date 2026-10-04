package de.uvsight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
                withContext(dispatcher) { ctl.onLine("""{"t":"hello","proto":18,"fw":"6.0","name":"UV-Sight","imu":true,"log":true,"id":"ABCDEF0123456789","sname":"Hoyt"}""") }
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
