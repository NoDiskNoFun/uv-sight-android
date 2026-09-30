package de.uvsight.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.uvsight.app.SightViewModel
import de.uvsight.app.TrainingStore
import de.uvsight.core.APP_VERSION
import de.uvsight.core.ArrowMark
import de.uvsight.core.FaceGeometry
import de.uvsight.core.FaceType
import de.uvsight.core.Hit
import de.uvsight.core.PhotoRecord
import de.uvsight.core.Pt
import de.uvsight.core.Scoring
import de.uvsight.core.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One arrow placed on the photo (display-bitmap pixels). */
private data class Mark(val pos: Offset, val ring: Int, val ringAuto: Int, val tool: String, val moved: Boolean = false, val source: String = "user")

/**
 * Score an end from a photo of the face: mark the face (centre + 5 or more points on the
 * blue ring's outer edge), then tap each arrow where it enters the face. Rings come from the
 * geometry; every mark can be moved, its ring changed or deleted. Works with a stylus or a
 * finger (a magnifier appears while a finger drags).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoScoringScreen(vm: SightViewModel, file: File, onClose: () -> Unit) {
    val uv = LocalUv.current
    val context = LocalContext.current
    val ctl = vm.controller
    val state by ctl.state.collectAsStateWithLifecycle()
    val prefs = state.photo

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullSize by remember { mutableStateOf(IntSize.Zero) }      // upright original size
    var rotation by remember { mutableIntStateOf(0) }
    var step by remember { mutableIntStateOf(1) }
    var faceType by remember { mutableStateOf(runCatching { FaceType.valueOf(prefs.face) }.getOrDefault(FaceType.WA40)) }
    var environment by remember { mutableStateOf(prefs.environment) }
    var center by remember { mutableStateOf<Offset?>(null) }
    var edge by remember { mutableStateOf(listOf<Offset>()) }
    var skipFace by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(listOf<Mark>()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var magPos by remember { mutableStateOf<Offset?>(null) }
    val modelStore = remember { de.uvsight.app.ModelStore(context) }
    val modelInfo = remember { modelStore.info() }
    var detecting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val geometry = remember(center, edge) { val c = center; if (c != null && edge.size >= 5) FaceGeometry.fit(Pt(c.x.toDouble(), c.y.toDouble()), edge.map { Pt(it.x.toDouble(), it.y.toDouble()) }) else null }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext
            val exifRot = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
            val upright = if (exifRot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(exifRot.toFloat()) }, true)
            val fw = if (exifRot % 180 == 0) bounds.outWidth else bounds.outHeight
            val fh = if (exifRot % 180 == 0) bounds.outHeight else bounds.outWidth
            rotation = exifRot; fullSize = IntSize(fw, fh); bitmap = upright
        }
    }
    // Fit the image into the container once both are known
    LaunchedEffect(bitmap, container) {
        val b = bitmap ?: return@LaunchedEffect
        if (container.width == 0) return@LaunchedEffect
        scale = min(container.width.toFloat() / b.width, container.height.toFloat() / b.height)
        offset = Offset((container.width - b.width * scale) / 2, (container.height - b.height * scale) / 2)
    }

    fun toImage(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
    fun toScreen(p: Offset) = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
    fun ringAt(p: Offset): Int = geometry?.let { g -> Scoring.ring(g.toFace(Pt(p.x.toDouble(), p.y.toDouble())), faceType, prefs.arrowMm) } ?: -1
    fun nearest(p: Offset, radiusPx: Float): Int? = marks.indices.minByOrNull { (marks[it].pos - p).getDistance() }?.takeIf { (marks[it].pos - p).getDistance() <= radiusPx }
    fun zoomAround(factor: Float, pivot: Offset) {
        val ns = (scale * factor).coerceIn(0.2f, 12f)
        offset = pivot - (pivot - offset) * (ns / scale)
        scale = ns
    }

    Column(Modifier.fillMaxSize().background(uv.paper).statusBarsPadding().navigationBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Text(if (step == 1) "Mark the face" else "Mark the arrows", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { zoomAround(0.7f, Offset(container.width / 2f, container.height / 2f)) }) { Text("−") }
            TextButton(onClick = { zoomAround(1.4f, Offset(container.width / 2f, container.height / 2f)) }) { Text("+") }
            TextButton(onClick = { bitmap?.let { b -> scale = min(container.width.toFloat() / b.width, container.height.toFloat() / b.height); offset = Offset((container.width - b.width * scale) / 2, (container.height - b.height * scale) / 2) } }) { Text("Fit") }
        }

        // Image with marks
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { container = it }
            .magnifier(sourceCenter = { magPos ?: Offset.Unspecified }, magnifierCenter = { magPos?.let { it - Offset(0f, 160.dp.toPx()) } ?: Offset.Unspecified }, zoom = 2.5f, size = DpSize(140.dp, 100.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tool = when (down.type) { PointerType.Stylus -> "stylus"; PointerType.Mouse -> "mouse"; else -> "finger" }
                    val startImg = toImage(down.position)
                    val dragIdx = if (step == 2) nearest(startImg, 28.dp.toPx() / scale) else null
                    var moved = false; var zoomed = false
                    val slop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val z = event.calculateZoom(); val pan = event.calculatePan(); val c = event.calculateCentroid()
                            if (z != 1f) zoomAround(z, c)
                            offset += pan
                            zoomed = true
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            val delta = ch.position - ch.previousPosition
                            if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                            if (moved && !zoomed) {
                                if (dragIdx != null) {
                                    val m = marks[dragIdx]
                                    val np = m.pos + delta / scale
                                    marks = marks.toMutableList().also { it[dragIdx] = m.copy(pos = np, ringAuto = ringAt(np), ring = if (m.ring == m.ringAuto || m.ringAuto == -1 && m.ring == -1) ringAt(np) else m.ring, moved = true) }
                                    selected = dragIdx
                                    if (tool == "finger") magPos = ch.position
                                } else offset += delta
                            }
                            ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    magPos = null
                    if (!moved && !zoomed) {
                        // a tap
                        if (step == 1) {
                            if (center == null) center = startImg else edge = edge + startImg
                        } else {
                            val hit = nearest(startImg, 24.dp.toPx() / scale)
                            if (hit != null) selected = hit
                            else { val r = ringAt(startImg); marks = marks + Mark(startImg, r, r, tool); selected = marks.size - 1 }
                        }
                    }
                }
            }) {
            bitmap?.let { b ->
                Image(b.asImageBitmap(), contentDescription = "Photo of the face", contentScale = ContentScale.None, alignment = Alignment.TopStart,
                    modifier = Modifier.graphicsLayer { translationX = offset.x; translationY = offset.y; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) })
            }
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(2.dp.toPx())
                geometry?.let { g ->
                    for (k in 1..10) {
                        val pts = g.circle(k / 6.0).map { toScreen(Offset(it.x.toFloat(), it.y.toFloat())) }
                        val col = if (k == 6) uv.blue else Color.White.copy(alpha = 0.55f)
                        for (i in pts.indices) drawLine(col, pts[i], pts[(i + 1) % pts.size], if (k == 6) 2.dp.toPx() else 1.dp.toPx())
                    }
                }
                center?.let { c -> val s = toScreen(c); drawLine(uv.gold, s - Offset(14f, 0f), s + Offset(14f, 0f), 3f); drawLine(uv.gold, s - Offset(0f, 14f), s + Offset(0f, 14f), 3f) }
                for (e in edge) drawCircle(uv.blue, 6.dp.toPx(), toScreen(e), style = stroke)
                marks.forEachIndexed { i, m ->
                    val s = toScreen(m.pos)
                    val sel = i == selected
                    drawCircle(if (sel) uv.gold else if (m.source == "model") uv.blue else Color.White, if (sel) 12.dp.toPx() else 10.dp.toPx(), s, style = Stroke(3.dp.toPx()))
                    drawCircle(uv.red, 3.dp.toPx(), s)
                    val label = if (m.ring < 0) "?" else Scoring.label(m.ring)
                    val tl = textMeasurer.measure(label, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White))
                    drawText(tl, topLeft = s + Offset(14.dp.toPx(), -tl.size.height / 2f))
                }
            }
        }

        // Bottom panel
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (step == 1) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (f in FaceType.values()) { FilterChip(selected = faceType == f, onClick = { faceType = f }, label = { Text(f.label) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                Row {
                    FilterChip(selected = environment == "outdoor", onClick = { environment = "outdoor" }, label = { Text("Outdoor") }, modifier = Modifier.padding(end = 6.dp))
                    FilterChip(selected = environment == "indoor", onClick = { environment = "indoor" }, label = { Text("Indoor") })
                }
                Text(when {
                    center == null -> "Tap the centre of the face."
                    edge.size < 5 -> "Tap 5 or more points on the outer edge of the blue ring (${edge.size} of 5)."
                    geometry == null -> "These points don't form an ellipse. Undo and tap again on the blue edge."
                    else -> "Face found. Add more edge points for precision, or continue."
                }, color = uv.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Undo", enabled = center != null) { if (edge.isNotEmpty()) edge = edge.dropLast(1) else center = null }
                    SecondaryButton("Skip face") { skipFace = true; center = null; edge = emptyList(); step = 2 }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Next", enabled = geometry != null) { ctl.setPhotoPrefs(prefs.copy(face = faceType.name, environment = environment)); step = 2 }
                }
            } else {
                val sightShots = state.session?.takeIf { it.active }?.endShots
                val hits = if (geometry != null) marks.map { m -> Scoring.mmFromCenter(geometry.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType) } else emptyList()
                val gc = Scoring.groupCenterMm(hits)
                Text("${marks.size} marked" + (sightShots?.let { " · sight counted $it" } ?: "") +
                    (gc?.let { " · group centre ${fmt(kotlin.math.abs(it.x) / 10, 1)} cm ${if (it.x < 0) "left" else "right"}, ${fmt(kotlin.math.abs(it.y) / 10, 1)} cm ${if (it.y < 0) "low" else "high"}" } ?: ""),
                    color = uv.muted, fontSize = 14.sp)
                if (skipFace) Text("Without the face marking, pick the ring of each arrow below.", color = uv.muted, fontSize = 13.sp)
                if (modelInfo == null) Text("Detect needs a detection model: import one under Settings → Photo scoring.", color = uv.muted, fontSize = 12.sp)
                val sel = selected
                if (sel != null && sel < marks.size) {
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        for (r in listOf(Scoring.X, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, Scoring.MISS)) {
                            FilterChip(selected = marks[sel].ring == r, onClick = { marks = marks.toMutableList().also { it[sel] = it[sel].copy(ring = r) } },
                                label = { Text(Scoring.label(r)) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        TextButton(onClick = { marks = marks.filterIndexed { i, _ -> i != sel }; selected = null }) { Text("Delete", color = uv.red) }
                    }
                } else Text("Tap each arrow where it enters the face. Tap a mark to change its ring, drag to move it.", color = uv.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton("Undo", enabled = marks.isNotEmpty()) { marks = marks.dropLast(1); selected = null }
                    if (!skipFace) SecondaryButton("Face") { step = 1 }
                    // Model proposals; greyed out until a model was imported under Settings
                    SecondaryButton(if (detecting) "…" else "Detect", enabled = modelInfo != null && !detecting && bitmap != null) {
                        val b = bitmap
                        if (b != null) {
                            detecting = true
                            scope.launch {
                                val found = withContext(Dispatchers.IO) { runCatching { de.uvsight.app.TfliteArrowDetector(modelStore.modelFile).use { it.detect(b, prefs.modelConf) } } }
                                detecting = false
                                found.onFailure { vm.toast("Detection failed: ${it.message}", true) }.onSuccess { dets ->
                                    val kept = marks.filter { it.source != "model" }
                                    val proposed = dets.map { d -> val p = Offset(d.x.toFloat(), d.y.toFloat()); val r = ringAt(p); Mark(p, r, r, "model", false, "model") }
                                    marks = kept + proposed
                                    selected = null
                                    vm.toast(if (proposed.isEmpty()) "No arrows found. Mark them by hand." else "${proposed.size} ${if (proposed.size == 1) "arrow" else "arrows"} proposed. Check and correct them.")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Use scores", enabled = marks.isNotEmpty() && marks.all { it.ring >= 0 }) {
                        val rings = marks.map { it.ring }
                        val hitList: List<Hit>? = geometry?.let { g -> marks.map { m -> val mm = Scoring.mmFromCenter(g.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType); Hit(mm.x, mm.y, m.ring) } }
                        ctl.applyPhotoScores(rings, hitList)
                        if (prefs.collect && bitmap != null) {
                            val b = bitmap!!
                            val k = fullSize.width.toDouble() / b.width
                            val exif = runCatching { ExifInterface(file.path) }.getOrNull()
                            val tags = listOf(ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_DATETIME, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MODEL, ExifInterface.TAG_WHITE_BALANCE)
                            val exifMap = tags.mapNotNull { t -> exif?.getAttribute(t)?.let { t to it } }.toMap()
                            val id = "photo-${System.currentTimeMillis()}"
                            val rec = PhotoRecord(
                                id = id, image = "$id.jpg", width = fullSize.width, height = fullSize.height, rotation = rotation,
                                face = faceType.name, faceDiameterMm = faceType.diameterMm, arrowMm = prefs.arrowMm,
                                center = center?.let { listOf(it.x * k, it.y * k) }, edge = edge.map { listOf(it.x * k, it.y * k) },
                                conic = geometry?.conic?.let { c -> listOf(c[0] / (k * k), c[1] / (k * k), c[2] / (k * k), c[3] / k, c[4] / k, c[5]) },
                                arrows = marks.mapIndexed { i, m ->
                                    val u = geometry?.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble()))
                                    val h = hitList?.getOrNull(i)
                                    ArrowMark(m.pos.x * k, m.pos.y * k, u?.x ?: Double.NaN, u?.y ?: Double.NaN, h?.mmX ?: Double.NaN, h?.mmY ?: Double.NaN, m.ringAuto, m.ring, m.tool, m.moved, m.source)
                                },
                                sightShots = sightShots, distM = state.distM, sessionKey = state.currentSessionKey, endN = state.session?.takeIf { it.active }?.end,
                                environment = environment, exif = exifMap, device = "${Build.MANUFACTURER} ${Build.MODEL}", app = APP_VERSION, timestamp = System.currentTimeMillis(),
                                model = if (marks.any { it.source == "model" }) modelInfo?.name else null, modelConf = if (marks.any { it.source == "model" }) prefs.modelConf else null,
                            )
                            runCatching { TrainingStore(context).save(rec, file) }.onFailure { vm.toast("Could not keep the photo: ${it.message}", true) }
                        }
                        file.delete()
                        onClose()
                    }
                }
            }
        }
    }
}
