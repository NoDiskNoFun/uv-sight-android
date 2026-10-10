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
import kotlin.math.roundToInt
import de.uvsight.core.ShotMatch
import de.uvsight.core.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import de.uvsight.core.tr

/** One arrow placed on the photo (display-bitmap pixels). */
private data class Mark(val pos: Offset, val ring: Int, val ringAuto: Int, val tool: String, val moved: Boolean = false, val source: String = "user")

/**
 * Score an end from a photo of the face: mark the face (centre + 4 or more points on the
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
    // the sight's light sensor tells indoor (artificial light, "dark" for the sensor) from outdoor; else the last choice
    var environment by remember { mutableStateOf(state.status?.takeIf { state.conn == de.uvsight.core.ConnState.CONNECTED }?.let { if (it.dark) "indoor" else "outdoor" } ?: prefs.environment) }
    var center by remember { mutableStateOf<Offset?>(null) }
    var edge by remember { mutableStateOf(listOf<Offset>()) }
    var skipFace by remember { mutableStateOf(false) }
    var faceRestored by remember { mutableStateOf(false) }   // marking taken over from the last photo
    var faceFromModel by remember { mutableStateOf(false) }  // marking proposed by the face finder
    var marks by remember { mutableStateOf(listOf<Mark>()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var magPos by remember { mutableStateOf<Offset?>(null) }
    val modelStore = remember { de.uvsight.app.ModelStore(context) }
    val modelInfo = remember { modelStore.info() }
    val hasArrowModel = remember { modelStore.hasArrows }
    val hasFaceModel = remember { modelStore.hasFace }
    var detections by remember { mutableStateOf<List<de.uvsight.core.Detection>?>(null) }   // arrow proposals, null until the model ran
    var faceLooked by remember { mutableStateOf(!hasFaceModel) }                             // the face finder has run (or there is none)
    var manualShot by remember { mutableStateOf(mapOf<Int, Int>()) }   // mark index -> shot index the user set by hand
    val endShots = state.endShots
    LaunchedEffect(step) { if (step == 2) ctl.requestShots() }
    var proposalsShown by remember { mutableStateOf(false) }
    val geometry = remember(center, edge) { val c = center; if (c != null && edge.size >= 4) FaceGeometry.fit(Pt(c.x.toDouble(), c.y.toDouble()), edge.map { Pt(it.x.toDouble(), it.y.toDouble()) }) else null }
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
            // The face finder proposes the centre and four edge points; without it (or when it finds
            // nothing) the marking of the last photo is taken over: the face lands in the same place
            // when the phone is held as before, so only a check or a small adjustment is needed.
            var proposed = false
            if (hasFaceModel) {
                val best = runCatching { de.uvsight.app.TflitePoseDetector(modelStore.faceFile).use { it.detect(upright, 0.3) } }.getOrNull()
                    ?.filter { it.kpts.size >= 5 }?.maxByOrNull { it.conf }
                if (best != null) {
                    val c = best.kpts[0]; val e = best.kpts.subList(1, 5)
                    if (FaceGeometry.fit(c, e) != null) {
                        center = Offset(c.x.toFloat(), c.y.toFloat()); edge = e.map { Offset(it.x.toFloat(), it.y.toFloat()) }
                        faceFromModel = true; proposed = true
                    }
                }
                faceLooked = true
            }
            val fm = prefs.faceMarks
            if (!proposed && fm != null && fm.size >= 10 && fm.size % 2 == 0) {
                center = Offset((fm[0] * upright.width).toFloat(), (fm[1] * upright.height).toFloat())
                edge = (2 until fm.size step 2).map { Offset((fm[it] * upright.width).toFloat(), (fm[it + 1] * upright.height).toFloat()) }
                faceRestored = true
            }
        }
    }
    /** On to the arrows; the marking is kept for the next photo, as fractions of the image. */
    fun goToArrows() {
        val b = bitmap; val c = center
        val fm = if (b != null && c != null) listOf(c.x / b.width.toDouble(), c.y / b.height.toDouble()) + edge.flatMap { listOf(it.x / b.width.toDouble(), it.y / b.height.toDouble()) } else prefs.faceMarks
        ctl.setPhotoPrefs(prefs.copy(face = faceType.name, environment = environment, faceMarks = fm)); step = 2
    }
    // A face the model found goes straight on to the arrows (once); the rings stay on the photo and Face leads back to check.
    var autoJumped by remember { mutableStateOf(false) }
    LaunchedEffect(faceFromModel, geometry) {
        if (faceFromModel && geometry != null && step == 1 && !skipFace && !autoJumped) { autoJumped = true; goToArrows(); vm.toast(tr("Face found by the model. Tap Face to check or adjust it.")) }
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
    // With a model installed, the arrows are looked for as soon as the photo is decoded; the
    // proposals appear when the arrow step is reached (the rings need the face marking).
    LaunchedEffect(bitmap) {
        val b = bitmap ?: return@LaunchedEffect
        if (!hasArrowModel) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { runCatching { de.uvsight.app.TflitePoseDetector(modelStore.arrowsFile).use { it.detect(b, prefs.modelConf) } } }
        found.onFailure { vm.toast(tr("Arrow detection failed: {message}", "message" to it.message), true); detections = emptyList() }
        found.onSuccess { detections = it }
    }
    LaunchedEffect(step, detections) {
        val dets = detections ?: return@LaunchedEffect
        if (step != 2 || proposalsShown) return@LaunchedEffect
        proposalsShown = true
        val proposed = dets.map { d -> val p = Offset(d.x.toFloat(), d.y.toFloat()); val r = ringAt(p); Mark(p, r, r, "model", false, "model") }
        marks = marks + proposed
        selected = null
        vm.toast(if (proposed.isEmpty()) tr("No arrows found. Mark them by hand.") else tr("{proposed} {proposed2} found. Check and correct them.", "proposed" to proposed.size, "proposed2" to (if (proposed.size == 1) tr("arrow") else tr("arrows"))))
    }
    // Marks placed before the face was (re)marked get their ring from the current geometry
    LaunchedEffect(geometry) {
        if (geometry == null || marks.isEmpty()) return@LaunchedEffect
        marks = marks.map { m -> val r = ringAt(m.pos); if (m.ring < 0 || m.ring == m.ringAuto) m.copy(ringAuto = r, ring = r) else m.copy(ringAuto = r) }
    }
    /** Positions of the marks in face mm (needs the face geometry). */
    fun hitsOf(ms: List<Mark>): List<Hit>? = geometry?.let { g -> ms.map { m -> val mm = Scoring.mmFromCenter(g.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType); Hit(mm.x, mm.y, m.ring) } }
    /** Shot of each mark: the sight's prediction matched to the positions, with the user's choices swapped in. */
    data class ShotAssign(val shotOf: List<Int>, val conf: List<Double>, val src: List<String>, val second: List<Int?>) {
        fun result() = ShotMatch.Result(shotOf, conf, second)
    }
    fun assignShots(ms: List<Mark>): ShotAssign? {
        val es = endShots ?: return null
        val hits = hitsOf(ms) ?: return null
        val r = ShotMatch.match(hits, es) ?: return null
        val shotOf = r.shotOf.toMutableList(); val conf = r.conf.toMutableList(); val src = MutableList(ms.size) { r.src(it) }
        for ((a, s) in manualShot) {
            if (a !in shotOf.indices || s !in shotOf.indices) continue
            val b = shotOf.indexOf(s)
            if (b >= 0 && b != a) { shotOf[b] = shotOf[a]; conf[b] = 1.0; src[b] = "user" }
            shotOf[a] = s; conf[a] = 1.0; src[a] = "user"
        }
        return ShotAssign(shotOf, conf, src, r.second)
    }
    fun nearestFacePoint(p: Offset, radiusPx: Float): Int? {
        val pts = listOfNotNull(center) + edge
        if (center == null) return null
        val i = pts.indices.minByOrNull { (pts[it] - p).getDistance() } ?: return null
        return i.takeIf { (pts[it] - p).getDistance() <= radiusPx }
    }
    val shotAssign: ShotAssign? = if (step == 2) assignShots(marks) else null
    fun nearest(p: Offset, radiusPx: Float): Int? = marks.indices.minByOrNull { (marks[it].pos - p).getDistance() }?.takeIf { (marks[it].pos - p).getDistance() <= radiusPx }
    fun zoomAround(factor: Float, pivot: Offset) {
        val ns = (scale * factor).coerceIn(0.2f, 12f)
        offset = pivot - (pivot - offset) * (ns / scale)
        scale = ns
    }

    Column(Modifier.fillMaxSize().background(uv.paper).statusBarsPadding().navigationBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(tr("Cancel")) }
            Text(if (step == 1) tr("Mark the face") else tr("Mark the arrows"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { zoomAround(0.7f, Offset(container.width / 2f, container.height / 2f)) }) { Text("−") }
            TextButton(onClick = { zoomAround(1.4f, Offset(container.width / 2f, container.height / 2f)) }) { Text("+") }
            TextButton(onClick = { bitmap?.let { b -> scale = min(container.width.toFloat() / b.width, container.height.toFloat() / b.height); offset = Offset((container.width - b.width * scale) / 2, (container.height - b.height * scale) / 2) } }) { Text(tr("Fit")) }
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
                    val faceIdx = if (step == 1) nearestFacePoint(startImg, 28.dp.toPx() / scale) else null
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
                                } else if (faceIdx != null) {
                                    if (faceIdx == 0) center = center?.plus(delta / scale)
                                    else edge = edge.toMutableList().also { it[faceIdx - 1] = it[faceIdx - 1] + delta / scale }
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
                    // which shot this arrow was: "3" sure, grey "3" a guess, "3/5" open
                    shotAssign?.let { sa ->
                        val sh = sa.shotOf[i] + 1
                        val txt = when (sa.src[i]) { "coin" -> "$sh/${(sa.second[i] ?: sa.shotOf[i]) + 1}"; else -> "$sh" }
                        val col = when (sa.src[i]) { "sure", "user" -> uv.gold; "guess" -> Color(0xFFBFC5BD); else -> Color(0xFF9AA39D) }
                        val sl = textMeasurer.measure(txt, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = col))
                        drawText(sl, topLeft = s + Offset(14.dp.toPx(), tl.size.height / 2f + 1.dp.toPx()))
                    }
                }
            }
        }

        // Bottom panel
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (step == 1) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (f in FaceType.values()) { FilterChip(selected = faceType == f, onClick = { faceType = f }, label = { Text(tr(f.label)) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                Row {
                    FilterChip(selected = environment == "outdoor", onClick = { environment = "outdoor" }, label = { Text(tr("Outdoor")) }, modifier = Modifier.padding(end = 6.dp))
                    FilterChip(selected = environment == "indoor", onClick = { environment = "indoor" }, label = { Text(tr("Indoor")) })
                }
                Text(when {
                    !faceLooked && center == null -> tr("Looking for the face…")
                    faceFromModel && geometry != null -> tr("Face found by the model. Drag a point to adjust, or Clear to mark anew.")
                    faceRestored && geometry != null -> tr("Marking from the last photo. Drag a point to adjust, or Clear to mark anew.")
                    center == null -> tr("Tap the centre of the face.")
                    edge.size < 4 -> tr("Tap 4 points on the outer edge of the blue ring ({edge} of 4), best top, bottom, left and right.", "edge" to edge.size)
                    geometry == null && edge.size == 4 -> tr("The 4 points don't fit an ellipse around the centre. Add a 5th point on the blue edge (that also handles a photo taken from the side), or Undo.")
                    geometry == null -> tr("These points don't form an ellipse. Undo and tap again, spread around the blue edge.")
                    edge.size == 4 -> tr("Face found. A 5th edge point also corrects a photo taken from the side; or continue.")
                    else -> tr("Face found. Add more edge points for precision, or continue.")
                }, color = uv.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(tr("Undo"), enabled = center != null) { if (edge.isNotEmpty()) edge = edge.dropLast(1) else center = null; faceRestored = false; faceFromModel = false }
                    SecondaryButton(tr("Clear"), enabled = center != null) { center = null; edge = emptyList(); faceRestored = false; faceFromModel = false }
                    SecondaryButton(tr("Skip face")) { skipFace = true; center = null; edge = emptyList(); step = 2 }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(tr("Next"), enabled = geometry != null) { goToArrows() }
                }
            } else {
                val sightShots = state.session?.takeIf { it.active }?.endShots
                val hits = if (geometry != null) marks.map { m -> Scoring.mmFromCenter(geometry.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType) } else emptyList()
                val gc = Scoring.groupCenterMm(hits)
                Text(tr("{marks} marked", "marks" to marks.size) + (sightShots?.let { " · sight counted $it" } ?: "") +
                    (gc?.let { " · group centre ${fmt(kotlin.math.abs(it.x) / 10, 1)} cm ${if (it.x < 0) "left" else "right"}, ${fmt(kotlin.math.abs(it.y) / 10, 1)} cm ${if (it.y < 0) "low" else "high"}" } ?: ""),
                    color = uv.muted, fontSize = 14.sp)
                if (skipFace) Text(tr("Without the face marking, pick the ring of each arrow below."), color = uv.muted, fontSize = 13.sp)
                if (hasArrowModel && detections == null) Text(tr("Looking for arrows…"), color = uv.muted, fontSize = 12.sp)
                if (endShots != null && shotAssign != null) {
                    val acc = ctl.matchAccuracy(state.setups?.active)
                    Text(tr("Shot matching accuracy ") + (acc?.let { "${(it * 100).roundToInt()} %" } ?: "–") + tr("  ·  tap an arrow to set its shot"), color = uv.muted, fontSize = 12.sp)
                } else if (endShots != null && marks.isNotEmpty() && endShots.shots.size != marks.size && geometry != null)
                    Text(tr("Shot matching: the sight counted {shots} {shots2}, {marks} marked.", "shots" to endShots.shots.size, "shots2" to (if (endShots.shots.size == 1) tr("shot") else tr("shots")), "marks" to marks.size), color = uv.muted, fontSize = 12.sp)
                val sel = selected
                if (sel != null && sel < marks.size) {
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        for (r in listOf(Scoring.X, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, Scoring.MISS)) {
                            FilterChip(selected = marks[sel].ring == r, onClick = { marks = marks.toMutableList().also { it[sel] = it[sel].copy(ring = r) } },
                                label = { Text(Scoring.label(r)) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        TextButton(onClick = { marks = marks.filterIndexed { i, _ -> i != sel }; selected = null }) { Text(tr("Delete"), color = uv.red) }
                    }
                    shotAssign?.let { sa ->
                        FlowRow(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.Center) {
                            Text(tr("Shot"), color = uv.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp, top = 12.dp))
                            for (sh in 0 until marks.size) FilterChip(selected = sa.shotOf[sel] == sh, onClick = { manualShot = manualShot + (sel to sh) },
                                label = { Text("${sh + 1}") }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                } else Text(tr("Tap each arrow where it enters the face. Tap a mark to change its ring, drag to move it."), color = uv.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton(tr("Undo"), enabled = marks.isNotEmpty()) { marks = marks.dropLast(1); selected = null }
                    if (!skipFace) SecondaryButton(tr("Face")) { step = 1 }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(tr("Use scores"), enabled = marks.isNotEmpty() && marks.all { it.ring >= 0 }) {
                        val rings = marks.map { it.ring }
                        val hitList: List<Hit>? = hitsOf(marks)
                        val sa = shotAssign
                        ctl.applyPhotoScores(rings, hitList, sa?.result(), sa?.src)
                        // Every scored photo is a training example, corrected model proposals included
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
                                    ArrowMark(m.pos.x * k, m.pos.y * k, u?.x ?: Double.NaN, u?.y ?: Double.NaN, h?.mmX ?: Double.NaN, h?.mmY ?: Double.NaN, m.ringAuto, m.ring, m.tool, m.moved, m.source,
                                        shot = sa?.shotOf?.getOrNull(i), shotConf = sa?.conf?.getOrNull(i), shotSrc = sa?.src?.getOrNull(i))
                                },
                                sightShots = sightShots, distM = state.distM, sessionKey = state.currentSessionKey, endN = state.session?.takeIf { it.active }?.end,
                                environment = environment, light = state.status?.takeIf { state.conn == de.uvsight.core.ConnState.CONNECTED }?.light, exif = exifMap, device = "${Build.MANUFACTURER} ${Build.MODEL}", app = APP_VERSION, timestamp = System.currentTimeMillis(),
                                model = if (marks.any { it.source == "model" }) modelInfo?.name else null, modelConf = if (marks.any { it.source == "model" }) prefs.modelConf else null,
                                shots = endShots?.shots,
                                faceSource = if (center == null) null else if (faceFromModel) "model" else if (faceRestored) "restored" else "user",
                                faceModel = if (center != null && faceFromModel) modelInfo?.face?.name ?: modelInfo?.name else null,
                            )
                            runCatching { TrainingStore(context).save(rec, file) }.onFailure { vm.toast(tr("Could not keep the photo: {message}", "message" to it.message), true) }
                        }
                        file.delete()
                        onClose()
                    }
                }
            }
        }
    }
}
