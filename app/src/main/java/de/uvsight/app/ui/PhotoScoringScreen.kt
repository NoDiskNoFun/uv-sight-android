package de.uvsight.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
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

/** One face of the photo (or one spot of a spot face): its tapped centre and edge points (display-bitmap pixels). */
private data class FaceMark(val center: Offset? = null, val edge: List<Offset> = emptyList()) {
    val geometry: FaceGeometry? get() = center?.takeIf { edge.size >= 4 }?.let { c -> FaceGeometry.fit(Pt(c.x.toDouble(), c.y.toDouble()), edge.map { Pt(it.x.toDouble(), it.y.toDouble()) }) }
}

/** One arrow placed on the photo (display-bitmap pixels). */
private data class Mark(val pos: Offset, val ring: Int, val ringAuto: Int, val tool: String, val moved: Boolean = false, val source: String = "user")

/**
 * Score an end from a photo of the face: mark the face (centre + 4 or more points on the
 * blue ring's outer edge; on a spot face each of the three spots in turn, top to bottom), then
 * tap each arrow where it enters the face. Rings come from the geometry of the nearest face;
 * every mark can be moved, its ring changed or deleted. Works with a stylus or a
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
    // The faces of the photo: one, or the three spots of a spot face, marked one after the other; faceIdx is the one being marked.
    var faces by remember { mutableStateOf(listOf(FaceMark())) }
    var faceIdx by remember { mutableIntStateOf(0) }
    val spotCount = faceType.spots
    fun curFace() = faces.getOrElse(faceIdx) { FaceMark() }
    fun setCur(f: FaceMark) { faces = faces.toMutableList().also { while (it.size <= faceIdx) it.add(FaceMark()); it[faceIdx] = f } }
    fun setCenter(c: Offset?) = setCur(curFace().copy(center = c))
    fun setEdge(e: List<Offset>) = setCur(curFace().copy(edge = e))
    // a change of face type between a spot face and a single face drops the spots beyond the count
    LaunchedEffect(spotCount) { if (faces.size > spotCount) { faces = faces.take(spotCount); faceIdx = minOf(faceIdx, spotCount - 1) } }
    var skipFace by remember { mutableStateOf(false) }
    var faceFromModel by remember { mutableStateOf(false) }  // marking proposed by the face finder
    var faceEdited by remember { mutableStateOf(false) }     // ... and a point of it dragged by hand: counts as hand-marked
    var arrowsEdited by remember { mutableStateOf(false) }   // a mark added, moved, re-ringed or deleted by hand after the proposals
    var marks by remember { mutableStateOf(listOf<Mark>()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var magPos by remember { mutableStateOf<Offset?>(null) }      // the finger: the magnifier's bottom-right corner sits under it
    var magSrc by remember { mutableStateOf<Offset?>(null) }      // the point being dragged: what the magnifier shows, centred
    val modelStore = remember { de.uvsight.app.ModelStore(context) }
    val modelInfo = remember { modelStore.info() }
    val hasArrowModel = remember { modelStore.hasArrows && prefs.useArrowModel }
    val hasFaceModel = remember { modelStore.hasFace && prefs.useFaceModel }
    var detections by remember { mutableStateOf<List<de.uvsight.core.Detection>?>(null) }   // arrow proposals, null until the model ran
    var faceLooked by remember { mutableStateOf(!hasFaceModel) }                             // the face finder has run (or there is none)
    var manualShot by remember { mutableStateOf(mapOf<Int, Int>()) }   // mark index -> shot index the user set by hand
    val endShots = state.endShots
    LaunchedEffect(step) { if (step == 2) ctl.requestShots() }
    var proposalsShown by remember { mutableStateOf(false) }
    val geometries = remember(faces) { faces.map { it.geometry } }          // one per face, null while a face is not marked yet
    val geometry = geometries.getOrNull(faceIdx)                               // the face being marked
    val allFaces = faces.size == spotCount && geometries.size == spotCount && geometries.all { it != null }
    // The gesture handler below is set up once (pointerInput(Unit)) and would keep the geometries of the first
    // composition, i.e. none: it reads the current ones through this state, so every tap gets its ring.
    val currentGeometries = rememberUpdatedState(geometries)
    /** The face an image point belongs to (the one it is nearest to in face units), with that geometry. */
    fun faceOf(p: Offset, gs: List<FaceGeometry?> = geometries): Pair<Int, FaceGeometry>? =
        gs.withIndex().mapNotNull { (i, g) -> g?.let { i to it } }.minByOrNull { (_, g) -> g.toFace(Pt(p.x.toDouble(), p.y.toDouble())).let { hypot(it.x, it.y) } }
    val currentFace = rememberUpdatedState(faceType)
    val currentArrowMm = rememberUpdatedState(prefs.arrowMm)
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
            // nothing) the face is marked by hand. (Taking over the last photo's marking was tried and dropped: it confused more than it helped.)
            if (hasFaceModel) {
                // the best face, or on a spot face up to three, top to bottom
                val found = runCatching { modelStore.detector(modelStore.faceFile).use { it.detect(upright, prefs.faceConf) } }.getOrNull()
                    ?.filter { it.kpts.size >= 5 && FaceGeometry.fit(it.kpts[0], it.kpts.subList(1, 5)) != null }
                    ?.sortedByDescending { it.conf }?.take(spotCount)?.sortedBy { it.kpts[0].y } ?: emptyList()
                // The finder takes a while with a large model. A marking begun by hand in the meantime, or the
                // arrow step already open, wins: the finder's faces are then dropped, not laid over the user's.
                val untouched = faces.all { it.center == null && it.edge.isEmpty() } && step == 1
                if (found.isNotEmpty() && untouched) {
                    val fm = found.map { d -> FaceMark(Offset(d.kpts[0].x.toFloat(), d.kpts[0].y.toFloat()), d.kpts.subList(1, 5).map { Offset(it.x.toFloat(), it.y.toFloat()) }) }
                    faces = if (fm.size < spotCount) fm + FaceMark() else fm
                    faceIdx = minOf(fm.size, spotCount - 1)     // the spots the finder missed are marked by hand
                    faceFromModel = true
                }
                faceLooked = true
            }
        }
    }
    /** On to the arrows; the first face's marking is kept for the next photo, as fractions of the image. */
    fun goToArrows() {
        val b = bitmap; val f = faces.firstOrNull(); val c = f?.center
        val fm = if (b != null && c != null) listOf(c.x / b.width.toDouble(), c.y / b.height.toDouble()) + f.edge.flatMap { listOf(it.x / b.width.toDouble(), it.y / b.height.toDouble()) } else prefs.faceMarks
        ctl.setPhotoPrefs(prefs.copy(face = faceType.name, environment = environment, faceMarks = fm)); step = 2
    }
    /** Next in the face step: the next spot of a spot face, or on to the arrows. */
    fun nextFace() { if (faceIdx < spotCount - 1) { faceIdx++; if (faces.size <= faceIdx) faces = faces + FaceMark() } else goToArrows() }
    // Faces the model found go straight on to the arrows (once); the rings stay on the photo and Face leads back to check.
    var autoJumped by remember { mutableStateOf(false) }
    LaunchedEffect(faceFromModel, allFaces) {
        if (faceFromModel && allFaces && step == 1 && !skipFace && !autoJumped) { autoJumped = true; goToArrows(); vm.toast(if (spotCount > 1) tr("Spots found by the model. Tap Face to check or adjust them.") else tr("Face found by the model. Tap Face to check or adjust it.")) }
    }
    // Fit the image into the container once both are known. The container changes height whenever the
    // panel below grows or shrinks (hints, ring chips): then the view only shifts to keep its centre,
    // the zoom the user set stays.
    var fittedFor by remember { mutableStateOf<Bitmap?>(null) }
    var lastContainer by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(bitmap, container) {
        val b = bitmap ?: return@LaunchedEffect
        if (container.width == 0) return@LaunchedEffect
        if (fittedFor !== b) {
            scale = min(container.width.toFloat() / b.width, container.height.toFloat() / b.height)
            offset = Offset((container.width - b.width * scale) / 2, (container.height - b.height * scale) / 2)
            fittedFor = b
        } else if (lastContainer.width > 0) {
            offset += Offset((container.width - lastContainer.width) / 2f, (container.height - lastContainer.height) / 2f)
        }
        lastContainer = container
    }

    fun toImage(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
    fun toScreen(p: Offset) = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
    fun ringAt(p: Offset): Int = faceOf(p, currentGeometries.value)?.let { (_, g) -> Scoring.ring(g.toFace(Pt(p.x.toDouble(), p.y.toDouble())), currentFace.value, currentArrowMm.value) } ?: -1
    // With a model installed, the arrows are looked for as soon as the photo is decoded; the
    // proposals appear when the arrow step is reached (the rings need the face marking).
    LaunchedEffect(bitmap) {
        val b = bitmap ?: return@LaunchedEffect
        if (!hasArrowModel) return@LaunchedEffect
        // a low floor here; how many proposals are taken is decided when the arrow step opens (the sight's shot count helps)
        val found = withContext(Dispatchers.IO) { runCatching { modelStore.detector(modelStore.arrowsFile).use { it.detect(b, minOf(prefs.modelConf, 0.15)) } } }
        found.onFailure { vm.toast(tr("Arrow detection failed: {message}", "message" to it.message), true); detections = emptyList() }
        found.onSuccess { detections = it }
    }
    LaunchedEffect(step, detections) {
        val dets = detections ?: return@LaunchedEffect
        if (step != 2 || proposalsShown) return@LaunchedEffect
        proposalsShown = true
        // Candidates above the confidence threshold; when the sight counted the shots of this end, that count wins:
        // too many proposals are cut to the most confident ones, too few are topped up with the next best.
        val expected = state.session?.takeIf { it.active }?.endShots?.takeIf { it > 0 } ?: endShots?.shots?.size?.takeIf { it > 0 }
        val sorted = dets.sortedByDescending { it.conf }
        val chosen = when {
            expected == null -> sorted.filter { it.conf >= prefs.modelConf }
            else -> sorted.take(maxOf(expected, sorted.count { it.conf >= prefs.modelConf }).coerceAtMost(sorted.size)).let { l -> if (l.size > expected) l.take(expected) else l }
        }
        val proposed = chosen.map { d -> val p = Offset(d.x.toFloat(), d.y.toFloat()); val r = ringAt(p); Mark(p, r, r, "model", false, "model") }
        marks = marks + proposed
        selected = null
        vm.toast(if (proposed.isEmpty()) tr("No arrows found. Mark them by hand.") else tr("{proposed} {proposed2} found. Check and correct them.", "proposed" to proposed.size, "proposed2" to (if (proposed.size == 1) tr("arrow") else tr("arrows"))) +
            (expected?.let { if (it != proposed.size) " " + tr("The sight counted {n}.", "n" to it) else "" } ?: ""))
    }
    // Marks placed before the face was (re)marked get their ring from the current geometry
    LaunchedEffect(geometries) {
        if (geometries.all { it == null } || marks.isEmpty()) return@LaunchedEffect
        marks = marks.map { m -> val r = ringAt(m.pos); if (m.ring < 0 || m.ring == m.ringAuto) m.copy(ringAuto = r, ring = r) else m.copy(ringAuto = r) }
    }
    /** Positions of the marks in face mm, each from the centre of its own face or spot (needs at least one face geometry). */
    fun hitsOf(ms: List<Mark>): List<Hit>? = if (geometries.all { it == null }) null else ms.map { m ->
        val g = faceOf(m.pos)!!.second; val mm = Scoring.mmFromCenter(g.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType); Hit(mm.x, mm.y, m.ring) }
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
        val f = curFace()
        val pts = listOfNotNull(f.center) + f.edge
        if (f.center == null) return null
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

    // The phone's back button leaves this screen, not the app
    BackHandler(onBack = onClose)
    // A Surface sets the text colour for the whole screen (outside the app's scaffold the default would be black, unreadable in dark mode)
    Surface(Modifier.fillMaxSize(), color = uv.paper, contentColor = uv.ink) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Top bar: compact buttons so the title keeps its room on a narrow screen
        val barButton = ButtonDefaults.textButtonColors(contentColor = uv.ink)
        val tight = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, colors = barButton, contentPadding = tight, modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text(tr("Cancel")) }
            FitText(if (step == 2) tr("Mark the arrows") else if (spotCount > 1) tr("Mark spot {n} of {count}", "n" to faceIdx + 1, "count" to spotCount) else tr("Mark the face"), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = uv.ink, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            TextButton(onClick = { zoomAround(0.7f, Offset(container.width / 2f, container.height / 2f)) }, colors = barButton, contentPadding = tight, modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text("−", fontSize = 18.sp) }
            TextButton(onClick = { zoomAround(1.4f, Offset(container.width / 2f, container.height / 2f)) }, colors = barButton, contentPadding = tight, modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text("+", fontSize = 18.sp) }
            TextButton(onClick = { bitmap?.let { b -> scale = min(container.width.toFloat() / b.width, container.height.toFloat() / b.height); offset = Offset((container.width - b.width * scale) / 2, (container.height - b.height * scale) / 2) } }, colors = barButton, contentPadding = tight, modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text(tr("Fit")) }
        }

        // Image with marks
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { container = it }
            // a square magnifier showing the dragged point itself (not the finger, which holds the point off centre),
            // placed so that its bottom-right corner sits under the finger: the point stays visible, the finger hides nothing
            .magnifier(sourceCenter = { magSrc ?: Offset.Unspecified }, magnifierCenter = { magPos?.let { it - Offset(100.dp.toPx(), 100.dp.toPx()) } ?: Offset.Unspecified },
                zoom = 3f, size = DpSize(200.dp, 200.dp), cornerRadius = 12.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tool = when (down.type) { PointerType.Stylus -> "stylus"; PointerType.Mouse -> "mouse"; else -> "finger" }
                    val startImg = toImage(down.position)
                    val dragIdx = if (step == 2) nearest(startImg, 28.dp.toPx() / scale) else null
                    val facePt = if (step == 1) nearestFacePoint(startImg, 28.dp.toPx() / scale) else null
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
                                    selected = dragIdx; arrowsEdited = true
                                    if (tool == "finger") { magPos = ch.position; magSrc = toScreen(np) }
                                } else if (facePt != null) {
                                    val f = curFace()
                                    if (facePt == 0) setCenter(f.center?.plus(delta / scale))
                                    else setEdge(f.edge.toMutableList().also { it[facePt - 1] = it[facePt - 1] + delta / scale })
                                    faceEdited = true
                                    if (tool == "finger") { magPos = ch.position; magSrc = (if (facePt == 0) curFace().center else curFace().edge.getOrNull(facePt - 1))?.let { toScreen(it) } }
                                } else offset += delta
                            }
                            ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    magPos = null; magSrc = null
                    if (!moved && !zoomed) {
                        // a tap
                        if (step == 1) {
                            val f = curFace(); if (f.center == null) setCenter(startImg) else setEdge(f.edge + startImg)
                        } else {
                            val hit = nearest(startImg, 24.dp.toPx() / scale)
                            if (hit != null) selected = hit
                            else { val r = ringAt(startImg); marks = marks + Mark(startImg, r, r, tool); selected = marks.size - 1; arrowsEdited = true }
                        }
                    }
                }
            }) {
            // The photo is drawn by hand with the view's shift and zoom. (An Image composable was clipped to the
            // container before the zoom applied, so on small screens only the photo's top-left corner showed.)
            val photo = remember(bitmap) { bitmap?.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                photo?.let { img -> withTransform({ translate(offset.x, offset.y); scale(scale, scale, pivot = Offset.Zero) }) { drawImage(img) } }
                val stroke = Stroke(2.dp.toPx())
                for (g in geometries) {
                    if (g == null) continue
                    // the printed ring boundaries, in units of the marked blue edge (radius 1)
                    val edgeK = faceType.edgeRings
                    for (k in 1..faceType.printedRings) {
                        val pts = g.circle(k.toDouble() / edgeK).map { toScreen(Offset(it.x.toFloat(), it.y.toFloat())) }
                        val col = if (k == edgeK) uv.blue else Color.White.copy(alpha = 0.55f)
                        for (i in pts.indices) drawLine(col, pts[i], pts[(i + 1) % pts.size], if (k == edgeK) 2.dp.toPx() else 1.dp.toPx())
                    }
                }
                // the face being marked in full colour, the other spots dimmed
                faces.forEachIndexed { fi, f ->
                    val cur = fi == faceIdx || step == 2
                    val gold = if (cur) uv.gold else uv.gold.copy(alpha = 0.45f); val blue = if (cur) uv.blue else uv.blue.copy(alpha = 0.45f)
                    f.center?.let { c -> val s = toScreen(c); drawLine(gold, s - Offset(14f, 0f), s + Offset(14f, 0f), 3f); drawLine(gold, s - Offset(0f, 14f), s + Offset(0f, 14f), 3f) }
                    for (e in f.edge) drawCircle(blue, 6.dp.toPx(), toScreen(e), style = stroke)
                }
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
                val f = curFace()
                val last = faceIdx >= spotCount - 1
                val spotWord = if (spotCount > 1) tr("spot") else tr("face")
                Text(when {
                    !faceLooked && f.center == null -> tr("Looking for the face… Tap its centre to mark it yourself; the finder's result is then ignored.")
                    faceFromModel && geometry != null -> tr("{face} found by the model. Drag a point to adjust, or Clear to mark anew.", "face" to spotWord.replaceFirstChar { it.uppercase() })
                    f.center == null -> if (spotCount > 1) tr("Tap the centre of spot {n}, counting from the top.", "n" to faceIdx + 1) else tr("Tap the centre of the face.")
                    f.edge.size < 4 -> tr("Tap 4 points on the outer edge of the blue ring ({edge} of 4), best top, bottom, left and right.", "edge" to f.edge.size)
                    geometry == null && f.edge.size == 4 -> tr("The 4 points don't fit an ellipse around the centre. Add a 5th point on the blue edge (that also handles a photo taken from the side), or Undo.")
                    geometry == null -> tr("These points don't form an ellipse. Undo and tap again, spread around the blue edge.")
                    !last -> tr("{face} {n} found. Next marks spot {next}.", "face" to spotWord.replaceFirstChar { it.uppercase() }, "n" to faceIdx + 1, "next" to faceIdx + 2)
                    f.edge.size == 4 -> tr("{face} found. A 5th edge point also corrects a photo taken from the side; or continue.", "face" to spotWord.replaceFirstChar { it.uppercase() })
                    else -> tr("{face} found. Add more edge points for precision, or continue.", "face" to spotWord.replaceFirstChar { it.uppercase() })
                }, color = uv.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                // the buttons wrap on a narrow screen; Next takes the rest of its line (a whole line of its own when wrapped)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Undo takes back the last point; on an empty spot it goes back to the spot before
                    SecondaryButton(tr("Undo"), enabled = f.center != null || faceIdx > 0) { if (f.edge.isNotEmpty()) setEdge(f.edge.dropLast(1)) else if (f.center != null) setCenter(null) else faceIdx--; faceFromModel = false }
                    SecondaryButton(tr("Clear"), enabled = f.center != null) { setCur(FaceMark()); faceFromModel = false }
                    SecondaryButton(tr("Skip face")) { skipFace = true; faces = listOf(FaceMark()); faceIdx = 0; step = 2 }
                    PrimaryButton(if (last) tr("Next") else tr("Next spot"), Modifier.weight(1f), enabled = geometry != null && (!last || allFaces)) { nextFace() }
                }
            } else {
                val sightShots = state.session?.takeIf { it.active }?.endShots
                // the group centre refers to one face; on a spot face the arrows sit on three, so it is left out there
                val hits = if (spotCount == 1 && geometry != null) marks.map { m -> Scoring.mmFromCenter(geometry.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble())), faceType) } else emptyList()
                val gc = Scoring.groupCenterMm(hits)
                Text(tr("{marks} marked", "marks" to marks.size) + (sightShots?.let { " · sight counted $it" } ?: "") +
                    (gc?.let { " · group centre ${fmt(kotlin.math.abs(it.x) / 10, 1)} cm ${if (it.x < 0) "left" else "right"}, ${fmt(kotlin.math.abs(it.y) / 10, 1)} cm ${if (it.y < 0) "low" else "high"}" } ?: ""),
                    color = uv.muted, fontSize = 14.sp)
                if (skipFace) Text(tr("Without the face marking, pick the ring of each arrow below."), color = uv.muted, fontSize = 13.sp)
                if (hasArrowModel && detections == null) Text(tr("Looking for arrows…"), color = uv.muted, fontSize = 12.sp)
                if (endShots != null && shotAssign != null) {
                    val acc = ctl.matchAccuracy(state.setups?.active)
                    Text(tr("Shot matching accuracy ") + (acc?.let { "${(it * 100).roundToInt()} %" } ?: "–") + tr("  ·  tap an arrow to set its shot"), color = uv.muted, fontSize = 12.sp)
                } else if (endShots != null && marks.isNotEmpty() && endShots.shots.size != marks.size && geometries.any { it != null })
                    Text(tr("Shot matching: the sight counted {shots} {shots2}, {marks} marked.", "shots" to endShots.shots.size, "shots2" to (if (endShots.shots.size == 1) tr("shot") else tr("shots")), "marks" to marks.size), color = uv.muted, fontSize = 12.sp)
                val sel = selected
                if (sel != null && sel < marks.size) {
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        for (r in listOf(Scoring.X, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, Scoring.MISS)) {
                            FilterChip(selected = marks[sel].ring == r, onClick = { marks = marks.toMutableList().also { it[sel] = it[sel].copy(ring = r) }; arrowsEdited = true },
                                label = { Text(Scoring.label(r)) }, modifier = Modifier.padding(end = 4.dp))
                        }
                        TextButton(onClick = { marks = marks.filterIndexed { i, _ -> i != sel }; selected = null; arrowsEdited = true }) { Text(tr("Delete"), color = uv.red) }
                    }
                    shotAssign?.let { sa ->
                        FlowRow(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.Center) {
                            Text(tr("Shot"), color = uv.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp, top = 12.dp))
                            for (sh in 0 until marks.size) FilterChip(selected = sa.shotOf[sel] == sh, onClick = { manualShot = manualShot + (sel to sh) },
                                label = { Text("${sh + 1}") }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                } else Text(tr("Tap each arrow where it enters the face. Tap a mark to change its ring, drag to move it."), color = uv.muted, fontSize = 13.sp)
                FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(tr("Undo"), enabled = marks.isNotEmpty()) { marks = marks.dropLast(1); selected = null; arrowsEdited = true }
                    if (!skipFace) SecondaryButton(tr("Face")) { faceIdx = 0; step = 1 }
                    PrimaryButton(tr("Use scores"), Modifier.weight(1f), enabled = marks.isNotEmpty() && marks.all { it.ring >= 0 }) {
                        val rings = marks.map { it.ring }
                        val hitList: List<Hit>? = hitsOf(marks)
                        val sa = shotAssign
                        ctl.applyPhotoScores(rings, hitList, sa?.result(), sa?.src)
                        // Every scored photo is kept; the trainer uses only what was marked by hand (see trainArrows / trainFace)
                        if (prefs.collect && bitmap != null) {
                            val b = bitmap!!
                            val k = fullSize.width.toDouble() / b.width
                            val exif = runCatching { ExifInterface(file.path) }.getOrNull()
                            val tags = listOf(ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_DATETIME, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MODEL, ExifInterface.TAG_WHITE_BALANCE)
                            val exifMap = tags.mapNotNull { t -> exif?.getAttribute(t)?.let { t to it } }.toMap()
                            val id = "photo-${System.currentTimeMillis()}"
                            // every marked face in full-image pixels (the first one also fills the old single-face fields)
                            val first = faces.firstOrNull()?.takeIf { it.center != null }
                            val recs = faces.mapIndexedNotNull { fi, f -> val c = f.center ?: return@mapIndexedNotNull null
                                de.uvsight.core.FaceMarkRec(listOf(c.x * k, c.y * k), f.edge.map { listOf(it.x * k, it.y * k) },
                                    geometries.getOrNull(fi)?.conic?.let { cc -> listOf(cc[0] / (k * k), cc[1] / (k * k), cc[2] / (k * k), cc[3] / k, cc[4] / k, cc[5]) }) }
                            val rec = PhotoRecord(
                                id = id, image = "$id.jpg", width = fullSize.width, height = fullSize.height, rotation = rotation,
                                face = faceType.name, faceDiameterMm = faceType.diameterMm, arrowMm = prefs.arrowMm,
                                center = first?.center?.let { listOf(it.x * k, it.y * k) }, edge = first?.edge?.map { listOf(it.x * k, it.y * k) } ?: emptyList(),
                                conic = recs.firstOrNull()?.conic,
                                spots = if (spotCount > 1) recs else null,
                                arrows = marks.mapIndexed { i, m ->
                                    val fo = faceOf(m.pos)
                                    val u = fo?.second?.toFace(Pt(m.pos.x.toDouble(), m.pos.y.toDouble()))
                                    val h = hitList?.getOrNull(i)
                                    ArrowMark(m.pos.x * k, m.pos.y * k, u?.x ?: Double.NaN, u?.y ?: Double.NaN, h?.mmX ?: Double.NaN, h?.mmY ?: Double.NaN, m.ringAuto, m.ring, m.tool, m.moved, m.source,
                                        shot = sa?.shotOf?.getOrNull(i), shotConf = sa?.conf?.getOrNull(i), shotSrc = sa?.src?.getOrNull(i), spot = if (spotCount > 1) fo?.first else null)
                                },
                                sightShots = sightShots, distM = state.distM, sessionKey = state.currentSessionKey, endN = state.session?.takeIf { it.active }?.end,
                                environment = environment, light = state.status?.takeIf { state.conn == de.uvsight.core.ConnState.CONNECTED }?.light, exif = exifMap, device = "${Build.MANUFACTURER} ${Build.MODEL}", app = APP_VERSION, timestamp = System.currentTimeMillis(),
                                model = if (marks.any { it.source == "model" }) modelInfo?.name else null, modelConf = if (marks.any { it.source == "model" }) prefs.modelConf else null,
                                shots = endShots?.shots,
                                // a part a model proposed counts as hand-marked once you corrected it (dragged a face point;
                                // added, moved, re-ringed or deleted an arrow); accepted untouched, it stays the model's
                                faceSource = if (first == null) null else if (faceFromModel) (if (faceEdited) "model-adjusted" else "model") else "user",
                                faceModel = if (first != null && faceFromModel) modelInfo?.face?.name ?: modelInfo?.name else null,
                                trainArrows = marks.isNotEmpty() && (marks.none { it.source == "model" } || arrowsEdited),
                                trainFace = first != null && (!faceFromModel || faceEdited),
                            )
                            runCatching { TrainingStore(context).save(rec, file) }.onFailure { vm.toast(tr("Could not keep the photo: {message}", "message" to it.message), true) }
                        }
                        vm.galleryScored = true          // the gallery original may go once the end is done
                        file.delete()
                        onClose()
                    }
                }
            }
        }
    }
    }   // Surface
}
