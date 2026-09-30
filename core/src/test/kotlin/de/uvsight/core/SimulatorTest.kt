package de.uvsight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the controller against the firmware running natively (the mock build of sketch.ino).
 * Runs only when SIGHT_BIN points to that binary; skipped otherwise.
 */
class SimulatorTest {
    private val bin = System.getenv("SIGHT_BIN")

    private class ProcTransport(private val proc: Process, private val scope: CoroutineScope) : Transport {
        val out = PrintWriter(proc.outputStream, true)
        var listener: SightController? = null
        override var connected = true
        override fun send(cmd: String) { out.println(cmd) }
        override suspend fun ensureConnected(timeoutMs: Long): Boolean { connected = true; scope.launch { listener?.onLinkUp() }; return true }
        override fun disconnect() { connected = false }
        fun control(line: String) = out.println(line)
    }

    @Test fun trainingSessionAgainstFirmware() {
        val path = bin ?: run { println("SIGHT_BIN not set, simulator test skipped"); return }
        assertTrue(File(path).canExecute())
        val proc = ProcessBuilder(path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val toasts = ArrayList<String>()
        val transport = ProcTransport(proc, scope)
        val ctl = SightController(scope, MemoryStore(), transport, object : UiSink { override fun toast(text: String, error: Boolean) { synchronized(toasts) { toasts.add(text) } } })
        transport.listener = ctl
        val lines = ArrayList<String>()
        val reader = Thread {
            BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { l -> synchronized(lines) { lines.add(l) }; scope.launch { ctl.onLine(l) } }
        }.apply { isDaemon = true; start() }
        fun sawLine(p: (String) -> Boolean) = synchronized(lines) { lines.any(p) }
        suspend fun waitFor(ms: Long = 5000, p: () -> Boolean): Boolean { val t = System.currentTimeMillis(); while (System.currentTimeMillis() - t < ms) { if (p()) return true; delay(50) }; return false }
        suspend fun <T> on(f: () -> T): T = withContext(dispatcher) { f() }
        try {
            runBlocking {
                on { ctl.connect() }
                assertTrue(waitFor { ctl.state.value.conn == ConnState.CONNECTED }, "connected")
                assertTrue(waitFor { ctl.state.value.cfg != null }, "settings arrived")
                assertTrue(waitFor { sawLine { it.contains("\"t\":\"logEnd\"") } }, "auto sync ran")
                assertEquals(23, ctl.state.value.cfg!!.size)
                assertEquals(null, ctl.state.value.protoWarning)
                on { ctl.setView(View.TRAINING) }
                on { ctl.counterOn() }
                assertTrue(waitFor { ctl.state.value.session?.counter == true }, "counter on")
                on { ctl.startSession() }
                assertTrue(waitFor { ctl.state.value.session?.active == true }, "session started")
                delay(1500)
                suspend fun tap(n: Int) { repeat(n) { transport.control("!tap"); delay(450) } }
                tap(3)
                assertTrue(waitFor { ctl.state.value.session?.endShots == 3 }, "3 shots counted")
                on { ctl.pushEntry("10"); ctl.pushEntry("9"); ctl.pushEntry("8") }
                on { ctl.sendEnd() }
                assertTrue(waitFor { ctl.state.value.lastEnd?.n == 1 && ctl.state.value.lastEnd?.sum == 27 }, "end 1 saved")
                assertTrue(waitFor { ctl.state.value.entries.isEmpty() }, "entries cleared")
                // mismatch -> save anyway
                tap(2)
                on { ctl.pushEntry("X"); ctl.pushEntry("9"); ctl.pushEntry("7") }
                on { ctl.sendEnd() }
                assertTrue(waitFor { ctl.state.value.confirm != null }, "mismatch question shown")
                assertEquals(false, ctl.state.value.confirm!!.canSplit)
                on { ctl.answerConfirm("yes") }
                assertTrue(waitFor { ctl.state.value.lastEnd?.n == 2 && ctl.state.value.lastEnd?.x == 1 }, "end 2 saved anyway")
                // split
                tap(6)
                assertTrue(waitFor { ctl.state.value.session?.endShots == 6 }, "6 shots")
                on { ctl.pushEntry("9"); ctl.pushEntry("9"); ctl.pushEntry("9") }
                on { ctl.sendEnd() }
                assertTrue(waitFor { ctl.state.value.confirm?.canSplit == true }, "split offered")
                on { ctl.answerConfirm("split") }
                assertTrue(waitFor { ctl.state.value.lastEnd?.n == 3 }, "end 3 by split")
                assertTrue(waitFor { ctl.state.value.session?.endShots == 3 }, "3 shots kept")
                on { ctl.pushEntry("8"); ctl.pushEntry("8"); ctl.pushEntry("8") }
                on { ctl.sendEnd() }
                assertTrue(waitFor { ctl.state.value.lastEnd?.n == 4 }, "end 4")
                // skip
                tap(2)
                on { ctl.skipEnd() }
                assertTrue(waitFor { ctl.state.value.lastEnd?.n == 5 && ctl.state.value.lastEnd?.valid == false }, "end 5 skipped")
                // end session
                on { ctl.endSession() }
                assertTrue(waitFor { ctl.state.value.sessions.size == 1 }, "session archived")
                val r = ctl.state.value.sessions[0]
                assertEquals(5, r.ends); assertEquals(12, r.scored); assertEquals(true, r.onSight)
                assertEquals(5, ctl.state.value.ends[r.key]!!.size)
                assertNotNull(r.endedAt)
                // clear the log on the sight, copy everything back
                on { ctl.clearSightLog() }
                assertTrue(waitFor { ctl.state.value.afterClearCount == 1 }, "cleared")
                on { ctl.afterClearAnswer(true) }
                assertTrue(waitFor(15000) { sawLine { it.contains("\"cmd\":\"putend\"") && it.contains("\"result\":\"added\"") } }, "ends copied back")
                assertTrue(waitFor(15000) { synchronized(toasts) { toasts.any { it.contains("copied to the sight") } } }, "copy finished")
                delay(500)
                assertEquals(5, synchronized(lines) { lines.count { it.contains("\"cmd\":\"putend\"") && it.contains("\"result\":\"added\"") } })
                assertTrue(waitFor { ctl.state.value.loginfo?.count == 1 }, "loginfo shows 1 session")
                val errs = synchronized(lines) { lines.filter { it.contains("\"t\":\"err\"") } }
                assertEquals(emptyList<String>(), errs)
                val csv = on { ctl.exportCsv() }
                assertEquals(6, csv.trim().lines().size)   // header + 5 ends
            }
        } finally {
            proc.destroy(); dispatcher.close()
        }
    }
}
