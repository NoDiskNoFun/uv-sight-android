package de.uvsight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Single mode: a badge made in the app survives a restart of the app and a connection to the sight. */
class BadgePersistTest {
    private class FakeTransport : Transport {
        val sent = ArrayList<String>()
        override var connected = true
        override fun send(cmd: String) { sent.add(cmd) }
        override suspend fun ensureConnected(timeoutMs: Long) = true
        override fun disconnect() { connected = false }
    }
    private val sink = object : UiSink { override fun toast(text: String, error: Boolean) {} }

    @Test fun ownBadgeSurvivesRestartAndConnection() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = MemoryStore()
        try {
            runBlocking {
                val ctl = SightController(scope, store, FakeTransport(), sink)
                withContext(dispatcher) {
                    ctl.setGamePrefs(GamePrefs(enabled = true))
                    val templates = ctl.state.value.badgeDefs.size
                    assertTrue(templates > 0, "templates seeded")
                    ctl.saveBadge(BadgeDef("abcd1234", "🎯", "Mine", "steady", "end", "min", listOf(60.0, 70.0, 80.0)))
                    ctl.saveGoal(Goals.defaultGoal().copy(id = "goal0001", target = 42.0))
                    assertEquals(templates + 1, ctl.state.value.badgeDefs.size)
                    assertTrue(store.get(SightController.BADGES_KEY)!!.contains("abcd1234"), "stored")
                }
                // the app restarts: a new controller over the same store
                val ctl2 = SightController(scope, store, FakeTransport(), sink)
                delay(100)
                val storeIds = Regex("\"id\":\"([^\"]+)\"").findAll(store.get(SightController.BADGES_KEY) ?: "").map { it.groupValues[1] }.toList()
                val decoded = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BadgeDef.serializer()), store.get(SightController.BADGES_KEY)!!).map { it.id } }
                val info = "store=" + storeIds + " decoded=" + decoded + " state=" + ctl2.state.value.badgeDefs.map { it.id } + " game=" + ctl2.state.value.game.enabled + " seeded=" + store.get(SightController.BADGES_SEEDED_KEY)
                assertTrue(ctl2.state.value.badgeDefs.any { it.id == "abcd1234" }, "still there after a restart: " + info)
                assertTrue(ctl2.state.value.goals.any { it.id == "goal0001" }, "the goal too: " + ctl2.state.value.goals.map { it.id })
                // a connection and its end change nothing
                withContext(dispatcher) {
                    ctl2.onLinkUp(); delay(100)
                    ctl2.onLine("""{"t":"hello","proto":24,"fw":"7.6","name":"Anchored","imu":true,"log":true,"id":"ABCDEF0123456789","sname":""}""")
                    ctl2.onLine("""{"t":"badgesStart","n":0}"""); ctl2.onLine("""{"t":"badgesEnd"}""")
                    delay(100)
                    assertTrue(ctl2.state.value.badgeDefs.any { it.id == "abcd1234" }, "still there while connected")
                    ctl2.onLinkDown(); delay(100)
                    assertTrue(ctl2.state.value.badgeDefs.any { it.id == "abcd1234" }, "still there after the link dropped")
                }
            }
        } finally { scope.cancel(); dispatcher.close() }
    }
}
