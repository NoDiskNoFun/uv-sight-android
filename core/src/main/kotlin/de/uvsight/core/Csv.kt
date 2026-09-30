package de.uvsight.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** CSV export/import and JSON backup, same columns and format as the web app (app 4.6+). */
object Csv {
    const val CSV_ARROWS = 12
    val COLUMNS: List<String> = listOf("date", "time", "session", "end", "distance_m", "valid", "skipped", "arrows", "points",
        "average", "x_count") + (1..CSV_ARROWS).map { "a$it" } + listOf("scores",
        "angle_deg", "angle_sd_deg", "angle_n", "cant_deg", "cant_max_deg", "canted", "cant_n",
        "session_ends", "session_arrows", "session_average", "session_x", "session_minutes", "session_shots",
        "session_invalid_ends", "session_invalid_arrows", "corrected", "ended_by_hand", "distance_source", "setup")

    fun field(v: Any?): String {
        if (v == null) return ""
        val t = v.toString()
        return if (t.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
    fun fix(v: Double?, n: Int): String = if (v == null || !v.isFinite()) "" else String.format(Locale.ROOT, "%.${n}f", v)
    fun arrowValue(v: String): Int = when (v) { "X" -> 10; "M" -> 0; else -> v.toIntOrNull() ?: 0 }
    private val dateFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.ROOT)

    fun export(sessions: List<ArchiveSession>, endsStore: Map<String, List<EndDetail>>, setupNameOf: (Int?) -> String): String {
        val list = sessions.filter { !it.hidden }.sortedWith(compareBy<ArchiveSession> { it.sortTime }.thenBy { it.id ?: 0 })
        val lines = ArrayList<String>()
        lines.add(COLUMNS.joinToString(","))
        for (r in list) {
            val start = r.startedAt?.let { Date(it) }
            val session = mapOf<String, Any?>(
                "date" to (start?.let { dateFmt.format(it) } ?: ""), "time" to (start?.let { timeFmt.format(it) } ?: ""),
                "session" to r.key, "session_ends" to r.ends, "session_arrows" to r.scored,
                "session_average" to fix(r.avg, 2), "session_x" to r.x, "session_minutes" to r.min, "session_shots" to r.shots,
                "session_invalid_ends" to r.invalidEnds, "session_invalid_arrows" to r.invalidArrows,
                "corrected" to if (r.mismatch) 1 else 0, "ended_by_hand" to if (r.manual) 1 else 0,
            )
            val ends = endsStore[r.key] ?: emptyList()
            val rows: List<EndDetail?> = if (ends.isEmpty()) listOf(null) else ends
            for (e in rows) {
                val row = session.toMutableMap()
                if (e != null) {
                    val scores = e.scores ?: emptyList()
                    row["end"] = e.n; row["distance_m"] = e.dist ?: ""; row["valid"] = if (e.valid) 1 else 0
                    row["skipped"] = if (e.reason == "skipped") 1 else 0; row["arrows"] = e.arrows
                    row["points"] = if (e.valid) e.sum else ""
                    row["average"] = if (e.valid && e.arrows > 0 && e.sum != null) fix(e.sum.toDouble() / e.arrows, 2) else ""
                    row["x_count"] = if (e.valid) e.x else ""; row["scores"] = scores.joinToString(" ")
                    row["angle_deg"] = fix(e.ang, 2); row["angle_sd_deg"] = fix(e.angSd, 2); row["angle_n"] = if (e.ang != null) e.angN else ""
                    row["cant_deg"] = fix(e.cant, 1); row["cant_max_deg"] = fix(e.cantMax, 1)
                    row["canted"] = if (e.cant != null) e.canted else ""; row["cant_n"] = if (e.cant != null) e.cantN else ""
                    row["distance_source"] = if (e.dist != null && e.dist > 0) (if (e.distAuto) "detected" else "set") else ""
                    row["setup"] = setupNameOf(e.setup)
                    scores.take(CSV_ARROWS).forEachIndexed { i, v -> row["a${i + 1}"] = arrowValue(v) }
                }
                lines.add(COLUMNS.joinToString(",") { field(row[it]) })
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    /** RFC 4180 parser: list of rows keyed by the header. */
    fun parse(text0: String): List<Map<String, String>> {
        var text = text0
        if (text.startsWith("﻿")) text = text.substring(1)
        val records = ArrayList<List<String>>()
        var row = ArrayList<String>(); val field = StringBuilder(); var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"' && i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                else if (c == '"') quoted = false
                else field.append(c)
            } else if (c == '"') quoted = true
            else if (c == ',') { row.add(field.toString()); field.setLength(0) }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(field.toString()); field.setLength(0)
                if (row.any { it.isNotBlank() }) records.add(row)
                row = ArrayList()
            } else field.append(c)
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); if (row.any { it.isNotBlank() }) records.add(row) }
        if (records.isEmpty()) return emptyList()
        val head = records[0].map { it.trim() }
        return records.drop(1).map { v -> head.indices.associate { head[it] to (v.getOrNull(it) ?: "").trim() } }
    }

    private fun num(v: String?): Int? = if (v.isNullOrEmpty()) null else v.toDoubleOrNull()?.toInt()
    private fun dbl(v: String?): Double? = if (v.isNullOrEmpty()) null else v.toDoubleOrNull()

    /** Per-end CSV (app 4.6+): rows grouped by session. */
    fun importEndsCsv(rows: List<Map<String, String>>, setupNames: Map<String, String>, now: Long): Pair<List<ArchiveSession>, Map<String, List<EndDetail>>> {
        val bySession = LinkedHashMap<String, MutableList<Map<String, String>>>()
        for (o in rows) { val s = o["session"] ?: continue; if (s.isEmpty()) continue; bySession.getOrPut(s) { ArrayList() }.add(o) }
        val sessions = ArrayList<ArchiveSession>(); val ends = HashMap<String, List<EndDetail>>()
        for ((key, list) in bySession) {
            val o = list[0]
            val parts = key.split("-")
            val ep = parts.getOrNull(0)?.toLongOrNull(); val id = parts.getOrNull(1)?.toLongOrNull()
            val valid = ep != null && id != null && ep > 0 && id > 0
            val start = parseLocal(o["date"], o["time"])
            val minutes = num(o["session_minutes"]) ?: 0
            sessions.add(ArchiveSession(
                key = key, epoch = if (valid) ep else null, id = if (valid) id else null,
                endedAt = start?.let { it + minutes * 60_000L }, importedAt = now,
                ends = num(o["session_ends"]) ?: 0, shots = num(o["session_shots"]) ?: 0, scored = num(o["session_arrows"]) ?: 0,
                avg = dbl(o["session_average"]) ?: 0.0, x = num(o["session_x"]) ?: 0, min = minutes,
                invalidEnds = num(o["session_invalid_ends"]) ?: 0, invalidArrows = num(o["session_invalid_arrows"]) ?: 0,
                mismatch = o["corrected"] == "1", manual = o["ended_by_hand"] == "1",
            ))
            val el = list.filter { !it["end"].isNullOrEmpty() }.map { r ->
                val v = r["valid"] == "1"
                val setupId = r["setup"]?.takeIf { it.isNotEmpty() }?.let { n -> setupNames.entries.firstOrNull { it.value == n }?.key?.toIntOrNull() }
                val hasAng = !r["angle_deg"].isNullOrEmpty(); val hasCant = !r["cant_deg"].isNullOrEmpty()
                EndDetail(
                    n = num(r["end"]) ?: 0, valid = v, arrows = num(r["arrows"]) ?: 0,
                    sum = if (v) num(r["points"]) ?: 0 else null, x = if (v) num(r["x_count"]) ?: 0 else null,
                    scores = if (v) r["scores"]?.split(" ")?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } else null,
                    reason = if (v) null else if (r["skipped"] == "1") "skipped" else "no scores",
                    dist = num(r["distance_m"])?.takeIf { it > 0 }, distAuto = r["distance_source"] == "detected", setup = setupId,
                    ang = if (hasAng) dbl(r["angle_deg"]) else null, angSd = if (hasAng) dbl(r["angle_sd_deg"]) else null,
                    angN = if (hasAng) num(r["angle_n"]) else null,
                    cant = if (hasCant) dbl(r["cant_deg"]) else null, cantMax = if (hasCant) dbl(r["cant_max_deg"]) else null,
                    canted = if (hasCant) num(r["canted"]) else null, cantN = if (hasCant) num(r["cant_n"]) else null,
                )
            }
            if (el.isNotEmpty()) ends[key] = el
        }
        return sessions to ends
    }

    /** Older per-session exports. */
    fun importSessionsCsv(rows: List<Map<String, String>>, now: Long): List<ArchiveSession> =
        rows.filter { it.containsKey("ends") && it.containsKey("average") }.map { o ->
            val epoch = num(o["epoch"])?.toLong() ?: 0; val id = num(o["id"])?.toLong() ?: 0; val minutes = num(o["minutes"]) ?: 0
            val start = o["start"]?.takeIf { it.isNotEmpty() }?.let { parseIso(it) }
            val key = if (epoch > 0 && id > 0) "$epoch-$id" else "csv-${o["start"] ?: o["fetched"]}-${o["ends"]}-${o["scored"]}-${o["average"]}"
            ArchiveSession(key = key, epoch = epoch.takeIf { it > 0 }, id = id.takeIf { it > 0 },
                endedAt = start?.let { it + minutes * 60_000L },
                importedAt = o["fetched"]?.takeIf { it.isNotEmpty() }?.let { parseIso(it) } ?: now,
                ends = num(o["ends"]) ?: 0, shots = num(o["shots"]) ?: 0, scored = num(o["scored"]) ?: 0, avg = dbl(o["average"]) ?: 0.0,
                x = num(o["x"]) ?: 0, min = minutes, invalidEnds = num(o["invalid_ends"]) ?: 0, invalidArrows = num(o["invalid_arrows"]) ?: 0,
                mismatch = o["corrected"] == "1", manual = o["ended_by_hand"] == "1")
        }

    private fun parseLocal(date: String?, time: String?): Long? {
        if (date.isNullOrEmpty()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("$date ${time?.takeIf { it.isNotEmpty() } ?: "00:00"}")?.time }.getOrNull()
    }
    private fun parseIso(s: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
    }.getOrNull() ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.ROOT).parse(s)?.time }.getOrNull()
        ?: parseLocal(s.take(10), s.drop(11).take(5))
}

@Serializable
data class Backup(
    val format: String = "uv-sight-backup",
    val version: Int = 1,
    val app: String = "",
    val exported: String = "",
    val sessions: List<ArchiveSession> = emptyList(),
    val ends: Map<String, List<EndDetail>> = emptyMap(),
    val deleted: List<String> = emptyList(),
)

object BackupFormat {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(b: Backup): String = json.encodeToString(Backup.serializer(), b)
    fun decode(text: String): Backup? = runCatching { json.decodeFromString(Backup.serializer(), text) }.getOrNull()
        ?.takeIf { it.format == "uv-sight-backup" }
}
