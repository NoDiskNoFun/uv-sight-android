package de.uvsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FixedClock(var t: Long) : Clock { override fun now() = t }

class ArchiveTest {
    private fun slot(id: Long, seq: Long = id, start: Long? = null, ago: Int? = null) =
        SlotMsg(seq, 42, id, start, ago, 5, 12, 12, 8.5, 1, 30, 0, 0, false, true)

    @Test fun archiveSlotDedups() {
        val a = Archive(MemoryStore(), FixedClock(1_000_000_000_000))
        assertTrue(a.archiveSlot(42, slot(1, ago = 10), false))
        assertFalse(a.archiveSlot(42, slot(1, ago = 10), false))
        assertEquals(1, a.sessions().size)
        val r = a.sessions()[0]
        assertEquals("42-1", r.key)
        assertEquals(1_000_000_000_000 - 10 * 60_000L, r.endedAt)
    }

    @Test fun deletedKeysStayOut() {
        val a = Archive(MemoryStore(), FixedClock(1_000_000_000_000))
        a.markDeleted("42-1")
        assertFalse(a.archiveSlot(42, slot(1), false))
        assertEquals(0, a.sessions().size)
        assertFalse(a.archiveSlot(42, slot(1), true))          // hidden fetch keeps it hidden
        assertEquals(1, a.sessions().size); assertTrue(a.sessions()[0].hidden)
    }

    @Test fun startTimeFillsDate() {
        val clock = FixedClock(2_000_000_000_000)
        val a = Archive(MemoryStore(), clock)
        a.rememberStart(SessionInfo(counter = true, active = true, epoch = 42, nextId = 3, min = 5))
        a.archiveSlot(42, slot(3, ago = null), false)
        assertEquals(2_000_000_000_000 - 5 * 60_000L + 30 * 60_000L, a.sessions()[0].endedAt)
    }

    @Test fun endRecMergeFillsGaps() {
        val a = Archive(MemoryStore(), FixedClock(1))
        a.recordEnd("42-1", EndMsg(1, true, 3, 27, 0, 9.0, null, null, null, null, null, null, null, null, null, null, null, true), listOf("10", "9", "8"))
        a.mergeEndRec(EndRecMsg(1, 42, 1, 1, true, 3, false, 27, 0, 50, false, 0, 1.5, 0.1, 3, null, null, null, null, "10 9 8"))
        val e = a.ends()["42-1"]!!.single()
        assertEquals(50, e.dist); assertEquals(1.5, e.ang!!, 1e-9); assertEquals(listOf("10", "9", "8"), e.scores)
        a.mergeEndRec(EndRecMsg(2, 42, 1, 2, false, 2, true, null, null, null, false, null, null, null, null, null, null, null, null, null))
        assertEquals("skipped", a.ends()["42-1"]!![1].reason)
    }

    @Test fun sightsAreAttributed() {
        val a = Archive(MemoryStore(), FixedClock(1_700_000_000_000))
        a.archiveSlot(42, slot(1, ago = 5), false, "S1")              // seen while connected to sight S1
        a.archiveSlot(42, slot(2, ago = 3), false)                    // from before the feature: no sight yet
        a.archiveSlot(77, slot(1, ago = 1), false)                    // another log key, another sight later
        assertEquals("S1", a.sessions().first { it.key == "42-1" }.sightId)
        assertEquals(null, a.sessions().first { it.key == "42-2" }.sightId)
        a.tagSight(42, "S1")
        assertEquals("S1", a.sessions().first { it.key == "42-2" }.sightId)
        assertEquals(null, a.sessions().first { it.key == "77-1" }.sightId)
        // the badge name travels through the CSV
        a.saveSights(listOf(SightInfo("S1", "Hoyt")))
        val csv = Csv.export(a.sessions(), a.ends(), { id -> a.sights().firstOrNull { it.id == id }?.label ?: "" }) { a.setupNameOf(it) }
        assertTrue(csv.lines()[0].endsWith(",sight_id,sight_name"))
        val rows = Csv.parse(csv)
        assertEquals(mapOf("S1" to "Hoyt"), Csv.sightsIn(rows))
        val (ss, _) = Csv.importEndsCsv(rows, emptyMap(), 5)
        assertEquals("S1", ss.first { it.key == "42-1" }.sightId)
        assertEquals(null, ss.first { it.key == "77-1" }.sightId)
    }

    @Test fun csvRoundTrip() {
        val a = Archive(MemoryStore(), FixedClock(1_700_000_000_000))
        a.archiveSlot(42, slot(1, start = 1_700_000_000), false)
        a.recordEnd("42-1", EndMsg(1, true, 3, 27, 1, 9.0, null, 50, "manual", 0, 1.25, 0.1, 3, -0.5, 1.0, 0, 3, true), listOf("X", "9", "8"))
        a.recordEnd("42-1", EndMsg(2, false, 2, null, null, null, "skipped", 50, "manual", 0, null, null, null, null, null, null, null, true), null)
        val csv = Csv.export(a.sessions(), a.ends()) { a.setupNameOf(it) }
        assertTrue(csv.startsWith("date,time,session,end,distance_m"))
        assertEquals(3, csv.trim().lines().size)
        val rows = Csv.parse(csv)
        val (ss, ee) = Csv.importEndsCsv(rows, emptyMap(), 5)
        assertEquals(1, ss.size); assertEquals("42-1", ss[0].key); assertEquals(8.5, ss[0].avg, 1e-9)
        val ends = ee["42-1"]!!
        assertEquals(2, ends.size)
        assertEquals(listOf("X", "9", "8"), ends[0].scores); assertEquals(50, ends[0].dist); assertEquals(1.25, ends[0].ang!!, 1e-9)
        assertEquals("skipped", ends[1].reason)
        // importing into a fresh archive
        val b = Archive(MemoryStore(), FixedClock(9))
        val res = b.importSessions(ss, ee, "CSV")
        assertEquals(1, res.added); assertEquals(2, b.ends()["42-1"]!!.size)
        assertEquals(0, b.importSessions(ss, ee, "CSV").added)
    }

    @Test fun backupRoundTrip() {
        val a = Archive(MemoryStore(), FixedClock(1_700_000_000_000))
        a.archiveSlot(42, slot(1, start = 1_700_000_000), false)
        a.markDeleted("42-9")
        val text = BackupFormat.encode(Backup(sessions = a.sessions(), ends = a.ends(), deleted = a.deleted().toList()))
        val b = BackupFormat.decode(text)
        assertNotNull(b); assertEquals(1, b.sessions.size); assertEquals(listOf("42-9"), b.deleted)
    }

    @Test fun csvFieldQuoting() {
        assertEquals("\"a,b\"", Csv.field("a,b")); assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\"")); assertEquals("", Csv.field(null))
    }
}
