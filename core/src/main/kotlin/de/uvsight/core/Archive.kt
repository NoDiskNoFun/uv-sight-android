package de.uvsight.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Everything the phone keeps about sessions: the archive, the end details, remembered
 * start times, removed keys, the log marker and setup names. Same rules as the web app.
 */
class Archive(private val store: KeyValueStore, private val clock: Clock = SystemClock) {
    companion object {
        const val ARCHIVE_KEY = "uvsight.archive.v1"
        const val ENDS_KEY = "uvsight.ends.v1"
        const val STARTS_KEY = "uvsight.starts.v1"
        const val DELETED_KEY = "uvsight.deleted.v1"
        const val LOGSEQ_KEY = "uvsight.logseq.v1"
        const val SETUP_NAMES_KEY = "uvsight.setupNames"
        const val DELETED_MAX = 1000
        const val STARTS_KEEP_MS = 90L * 24 * 3600 * 1000
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }

    private val sessionsSer = ListSerializer(ArchiveSession.serializer())
    private val endsSer = MapSerializer(String.serializer(), ListSerializer(EndDetail.serializer()))
    private val startsSer = MapSerializer(String.serializer(), Long.serializer())
    private val stringsSer = ListSerializer(String.serializer())
    private val namesSer = MapSerializer(String.serializer(), String.serializer())

    private inline fun <T> load(key: String, ser: kotlinx.serialization.KSerializer<T>, empty: () -> T): T =
        store.get(key)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: empty()

    fun sessions(): List<ArchiveSession> = load(ARCHIVE_KEY, sessionsSer) { emptyList() }
    fun saveSessions(list: List<ArchiveSession>) = store.put(ARCHIVE_KEY, json.encodeToString(sessionsSer, list))
    fun ends(): Map<String, List<EndDetail>> = load(ENDS_KEY, endsSer) { emptyMap() }
    fun saveEnds(m: Map<String, List<EndDetail>>) = store.put(ENDS_KEY, json.encodeToString(endsSer, m))
    fun starts(): Map<String, Long> = load(STARTS_KEY, startsSer) { emptyMap() }
    private fun saveStarts(m: Map<String, Long>) = store.put(STARTS_KEY, json.encodeToString(startsSer, m))
    fun deleted(): Set<String> = load(DELETED_KEY, stringsSer) { emptyList() }.toSet()
    private fun saveDeleted(s: Collection<String>) = store.put(DELETED_KEY, json.encodeToString(stringsSer, s.toList()))
    fun setupNames(): Map<String, String> = load(SETUP_NAMES_KEY, namesSer) { emptyMap() }
    fun saveSetupNames(m: Map<String, String>) = store.put(SETUP_NAMES_KEY, json.encodeToString(namesSer, m))
    fun setupNameOf(id: Int?): String = setupNames()[(id ?: 0).toString()] ?: if (id != null && id != 0) "Setup ${id + 1}" else "Standard"

    var logSeq: Long
        get() = store.get(LOGSEQ_KEY)?.toLongOrNull() ?: 0
        set(v) = store.put(LOGSEQ_KEY, v.toString())

    fun markDeleted(key: String) { saveDeleted((deleted() + key).toList().takeLast(DELETED_MAX)) }
    fun unmarkDeleted(key: String) { saveDeleted(deleted() - key) }

    /** A running session reports the key it will get; its start is remembered for the date. */
    fun rememberStart(s: SessionInfo) {
        val key = s.key ?: return
        val starts = starts().toMutableMap()
        if (starts.containsKey(key)) return
        val now = clock.now()
        starts[key] = now - s.min * 60_000L
        val cutoff = now - STARTS_KEEP_MS
        starts.entries.removeAll { it.value < cutoff }
        saveStarts(starts)
    }

    /** Fill missing dates from remembered start times; true if something changed. */
    fun resolveDates(): Boolean {
        val starts = starts()
        val list = sessions()
        var changed = false
        val out = list.map { r ->
            val st = starts[r.key]
            if (r.endedAt == null && st != null) { changed = true; r.copy(endedAt = st + r.min * 60_000L) } else r
        }
        if (changed) saveSessions(out)
        return changed
    }

    /**
     * Store a session from the sight. Returns true if it was new.
     * hiddenFetch: a full log answer is running to recover sessions removed before their data was kept.
     */
    fun archiveSlot(epoch: Long?, sl: SlotMsg, hiddenFetch: Boolean): Boolean {
        val list = sessions().toMutableList()
        val key = "$epoch-${sl.id}"
        val now = clock.now()
        if (deleted().contains(key)) {
            if (hiddenFetch && list.none { it.key == key }) {
                list.add(fromSlot(key, epoch, sl, sl.start?.let { it * 1000 + sl.min * 60_000L }, now).copy(hidden = true))
                saveSessions(list)
            }
            return false
        }
        var endedAt: Long? = null
        if (sl.start != null && sl.start > 0) endedAt = sl.start * 1000 + sl.min * 60_000L
        else if (sl.ago != null) endedAt = now - sl.ago * 60_000L
        if (endedAt == null) starts()[key]?.let { endedAt = it + sl.min * 60_000L }
        val idx = list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val found = list[idx]
            if (found.endedAt == null && endedAt != null) { list[idx] = found.copy(endedAt = endedAt); saveSessions(list) }
            return false
        }
        list.add(fromSlot(key, epoch, sl, endedAt, now))
        saveSessions(list)
        return true
    }

    private fun fromSlot(key: String, epoch: Long?, sl: SlotMsg, endedAt: Long?, now: Long) = ArchiveSession(
        key = key, epoch = epoch, id = sl.id, endedAt = endedAt, importedAt = now, ends = sl.ends, shots = sl.shots,
        scored = sl.scored, avg = sl.avg, x = sl.x, min = sl.min, invalidEnds = sl.invalidEnds,
        invalidArrows = sl.invalidArrows, mismatch = sl.mismatch, manual = sl.manual,
    )

    fun markOnSight(keys: Set<String>, value: Boolean) {
        if (keys.isEmpty()) return
        val list = sessions()
        var changed = false
        val out = list.map { r -> if (keys.contains(r.key) && (r.onSight ?: false) != value) { changed = true; r.copy(onSight = value) } else r }
        if (changed) saveSessions(out)
    }

    fun update(key: String, f: (ArchiveSession) -> ArchiveSession) {
        val list = sessions().toMutableList()
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0) { list[i] = f(list[i]); saveSessions(list) }
    }

    fun removeSession(key: String) { saveSessions(sessions().filter { it.key != key }); saveEnds(ends() - key) }

    /** An end scored in this app, for the running session. */
    fun recordEnd(key: String, m: EndMsg, sentScores: List<String>?) {
        val all = ends().toMutableMap()
        val list = (all[key] ?: emptyList()).filter { it.n != m.n }.toMutableList()
        val rec = EndDetail(
            n = m.n, valid = m.valid, arrows = m.arrows,
            sum = if (m.valid) m.sum else null, x = if (m.valid) m.x else null,
            scores = if (m.valid && sentScores != null && sentScores.size == m.arrows) sentScores else null,
            reason = if (m.valid) null else m.reason,
            dist = m.dist, distAuto = m.distSrc == "auto", setup = m.setup,
            ang = m.ang, angSd = m.angSd, angN = m.angN, cant = m.cant, cantMax = m.cantMax, canted = m.canted, cantN = m.cantN,
        )
        list.add(rec)
        list.sortBy { it.n }
        all[key] = list
        saveEnds(all)
    }

    /** An end stored on the sight: add it, or fill in what the phone doesn't know yet. */
    fun mergeEndRec(m: EndRecMsg) {
        val key = "${m.epoch}-${m.id}"
        val all = ends().toMutableMap()
        val list = (all[key] ?: emptyList()).toMutableList()
        var i = list.indexOfFirst { it.n == m.n }
        if (i < 0) {
            list.add(EndDetail(
                n = m.n, valid = m.valid, arrows = m.arrows,
                sum = if (m.valid) m.sum else null, x = if (m.valid) m.x else null,
                scores = if (m.valid) m.scores?.split(" ")?.filter { it.isNotEmpty() }?.map { if (it == "0") "M" else it } else null,
                reason = if (m.valid) null else if (m.skipped) "skipped" else "no scores",
            ))
            list.sortBy { it.n }
            i = list.indexOfFirst { it.n == m.n }
        }
        var e = list[i]
        if (e.dist == null && m.dist != null) e = e.copy(dist = m.dist, distAuto = m.auto)
        if (e.setup == null && m.setup != null) e = e.copy(setup = m.setup)
        if (e.ang == null && m.ang != null) e = e.copy(ang = m.ang, angSd = m.angSd, angN = m.angN)
        if (e.cant == null && m.cant != null) e = e.copy(cant = m.cant, cantMax = m.cantMax, canted = m.canted, cantN = m.cantN)
        list[i] = e
        all[key] = list
        saveEnds(all)
    }

    /** Merge sessions (and their ends) from a backup or CSV; returns counts. */
    fun importSessions(sessions: List<ArchiveSession>, ends: Map<String, List<EndDetail>>?, what: String): ImportResult {
        val list = sessions().toMutableList()
        val del = deleted()
        val e = ends().toMutableMap()
        val byKey = list.associateBy { it.key }.toMutableMap()
        var added = 0; var filled = 0; var skipped = 0
        for (s in sessions) {
            if (s.key.isEmpty()) continue
            if (del.contains(s.key)) { skipped++; continue }
            val have = byKey[s.key]
            if (have != null) {
                if (have.endedAt == null && s.endedAt != null) {
                    val idx = list.indexOfFirst { it.key == s.key }
                    list[idx] = have.copy(endedAt = s.endedAt); byKey[s.key] = list[idx]; filled++
                }
            } else {
                val rec = s.copy(importedAt = if (s.importedAt > 0) s.importedAt else clock.now())
                list.add(rec); byKey[s.key] = rec; added++
            }
            val se = ends?.get(s.key)
            if (se != null && !e.containsKey(s.key)) e[s.key] = se
        }
        saveSessions(list)
        saveEnds(e)
        return ImportResult(added, filled, skipped, what)
    }
}
