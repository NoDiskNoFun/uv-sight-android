package de.uvsight.app

import android.content.Context
import de.uvsight.core.Coco
import de.uvsight.core.PhotoRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Photos and marks kept as training data (Settings → Photo scoring → Collect training data).
 * One JPEG and one JSON per scored photo in the app's private storage; export as a ZIP that
 * also carries a COCO file over everything.
 */
class TrainingStore(context: Context) {
    private val dir = File(context.filesDir, "training").apply { mkdirs() }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun save(record: PhotoRecord, photo: File): PhotoRecord {
        val img = File(dir, "${record.id}.jpg")
        photo.copyTo(img, overwrite = true)
        val r = record.copy(image = img.name)
        File(dir, "${record.id}.json").writeText(json.encodeToString(PhotoRecord.serializer(), r))
        return r
    }

    fun records(): List<PhotoRecord> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .sortedBy { it.name }
        .mapNotNull { f -> runCatching { json.decodeFromString(PhotoRecord.serializer(), f.readText()) }.getOrNull() }

    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun exportZip(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val recs = records()
            for (r in recs) {
                val img = File(dir, r.image)
                if (img.exists()) { zip.putNextEntry(ZipEntry("images/${r.image}")); img.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                zip.putNextEntry(ZipEntry("records/${r.id}.json")); zip.write(json.encodeToString(PhotoRecord.serializer(), r).toByteArray()); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("coco.json")); zip.write(Coco.build(recs).toByteArray()); zip.closeEntry()
        }
    }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }
}
