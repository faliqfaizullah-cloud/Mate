package com.mate.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

val StyleYellow = Color(0xFFE9F23B)
private val PanelBg = Color(0xFF0E0E0E)
private val Btn = Color(0xFF262626)
private val Grey = Color(0xFF8E8E93)

// ------------------------------------------------------------------------------------------------
// State + colour maths
// ------------------------------------------------------------------------------------------------

class BaseStyle(val name: String, val tint: Color, val matrix: () -> ColorMatrix?)

val BaseStyles = listOf(
    BaseStyle("Standard", Color(0xFFFF8A4C)) { null },
    BaseStyle("Rich Contrast", Color(0xFF5B7CFF)) { sat(1.1f, contrast(1.22f, -6f)) },
    BaseStyle("Vibrant", Color(0xFFB05CFF)) { sat(1.55f, contrast(1.05f)) },
    BaseStyle(
        "Cool", Color(0xFF4FD1B5)
    ) {
        sat(
            1.05f, cm(
                0.92f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.10f, 0f, 4f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    },
    BaseStyle("Muted", Color(0xFF9A9A9A)) { sat(0.7f, contrast(0.92f, 8f)) },
)

/** One complete "photographic style": base look + nuance pad + material levels. */
class StyleState {
    var base by mutableIntStateOf(0)
    var x by mutableFloatStateOf(0f)          // colour: -1 cool … +1 warm
    var y by mutableFloatStateOf(0f)          // tone:   -1 deeper … +1 brighter
    var intensity by mutableFloatStateOf(0.5f)
    var soft by mutableFloatStateOf(0f)
    var film by mutableFloatStateOf(0f)
    var grain by mutableFloatStateOf(0f)

    val strength get() = intensity * 2f
    val active get() = base != 0 || x != 0f || y != 0f || soft > 0f || film > 0f || grain > 0f
    val nuanceChanged get() = x != 0f || y != 0f || intensity != 0.5f

    fun copyFrom(o: StyleState) {
        base = o.base; x = o.x; y = o.y; intensity = o.intensity
        soft = o.soft; film = o.film; grain = o.grain
    }

    fun reset() = copyFrom(StyleState())
    fun resetNuance() { x = 0f; y = 0f; intensity = 0.5f }
    fun sig() = listOf(base.toFloat(), x, y, intensity, soft, film, grain)
}

private fun translate(t: Float) = cm(
    1f, 0f, 0f, 0f, t,
    0f, 1f, 0f, 0f, t,
    0f, 0f, 1f, 0f, t,
    0f, 0f, 0f, 1f, 0f,
)

fun styleMatrix(st: StyleState): ColorMatrix? {
    if (!st.active) return null
    val m = ColorMatrix()
    BaseStyles[st.base].matrix()?.let { m.postConcat(it) }
    val k = st.strength
    val w = st.x * k
    val tn = st.y * k
    if (w != 0f) m.postConcat(
        cm(
            1f + 0.10f * w, 0f, 0f, 0f, 0f,
            0f, 1f + 0.01f * w, 0f, 0f, 0f,
            0f, 0f, 1f - 0.13f * w, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    if (tn != 0f) {
        m.postConcat(translate(tn * 28f))
        m.postConcat(contrast(1f - 0.06f * tn))
    }
    if (st.soft > 0f) {
        val f = st.soft
        m.postConcat(contrast(1f - 0.12f * f, 6f * f))
        m.postConcat(sat(1f - 0.05f * f))
        m.postConcat(
            cm(
                1f + 0.03f * f, 0f, 0f, 0f, 2f * f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - 0.02f * f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }
    if (st.film > 0f) {
        val f = st.film
        m.postConcat(sat(1f - 0.18f * f))
        m.postConcat(contrast(1f - 0.10f * f, 12f * f))
        m.postConcat(
            cm(
                1f + 0.05f * f, 0f, 0f, 0f, 4f * f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - 0.05f * f, 0f, 8f * f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }
    return m
}

/** Filter + style as one matrix (null = nothing to apply). */
fun combined(filterIdx: Int, st: StyleState): ColorMatrix? {
    val f = Filters[filterIdx].matrix
    val s = styleMatrix(st)
    if (f == null && s == null) return null
    return ColorMatrix().apply { f?.let { postConcat(it) }; s?.let { postConcat(it) } }
}

/** Small tile of random speckle, used for the grain overlay (live and baked). */
fun makeNoise(size: Int = 256): Bitmap {
    val px = IntArray(size * size) {
        val v = if (Random.nextFloat() > 0.5f) 255 else 0
        val a = (Random.nextFloat() * 255).toInt()
        (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Bakes colour style, soft-skin softening and film grain into a saved JPEG (EXIF rotation applied). */
suspend fun applyStyle(ctx: Context, uri: Uri, matrix: ColorMatrix?, grain: Float, soft: Float) =
    withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching
            val orientation = cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            val upright = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val w = upright.width
            val h = upright.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = AndroidCanvas(out)
            c.drawBitmap(upright, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                if (matrix != null) colorFilter = ColorMatrixColorFilter(matrix)
            })
            if (soft > 0f) {
                val small = Bitmap.createScaledBitmap(out, (w / 5).coerceAtLeast(1), (h / 5).coerceAtLeast(1), true)
                c.drawBitmap(small, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (soft * 0.45f * 255).toInt() })
            }
            if (grain > 0f) {
                val shader = BitmapShader(makeNoise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val sc = max(1f, w / 540f)
                shader.setLocalMatrix(Matrix().apply { setScale(sc, sc) })
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                    this.shader = shader; alpha = (grain * 0.35f * 255).toInt()
                })
            }
            cr.openOutputStream(uri, "wt")?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
        Unit
    }

// ------------------------------------------------------------------------------------------------
// UI
// ------------------------------------------------------------------------------------------------

@Composable
fun StylePanel(draft: StyleState, applied: StyleState, onClose: () -> Unit, h: Haptics, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var help by remember { mutableStateOf(false) }

    Column(modifier.background(PanelBg)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundBtn(Btn, Modifier.size(34.dp), { h.click(); help = !help }) {
                Text("?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Segmented(tab) { tab = it; h.tick() }
            Spacer(Modifier.weight(1f))
            val dirty = draft.active
            RoundBtn(Btn, Modifier.size(34.dp), { if (dirty) { draft.reset(); h.double() } else h.tick() }) {
                ResetGlyph(if (dirty) Color.White else Color(0xFF555555))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262626)))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (tab == 0) NuanceTab(draft, h) else MaterialTab(draft, h)
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundBtn(Btn, Modifier.size(44.dp), { draft.copyFrom(applied); h.click(); onClose() }) {
                Canvas(Modifier.size(14.dp)) {
                    val s = 2.2.dp.toPx(); val c = Color(0xFFFF3B5C)
                    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), s, StrokeCap.Round)
                    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
                }
            }
            Text(
                if (help) "DRAG THE PAD · SWIPE FOR STYLES" else "${BaseStyles[draft.base].name.uppercase()} STYLE",
                Modifier.weight(1f), color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            RoundBtn(Btn, Modifier.size(44.dp), { applied.copyFrom(draft); h.confirm(); onClose() }) {
                Canvas(Modifier.size(16.dp)) {
                    val s = 2.4.dp.toPx()
                    drawLine(StyleYellow, Offset(0f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.9f), s, StrokeCap.Round)
                    drawLine(StyleYellow, Offset(size.width * 0.38f, size.height * 0.9f), Offset(size.width, size.height * 0.1f), s, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(bg: Color, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.clip(CircleShape).background(bg).tap(onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ResetGlyph(c: Color) = Canvas(Modifier.size(15.dp)) {
    val s = 1.8.dp.toPx()
    drawArc(c, -60f, 300f, false, Offset(s, s), Size(size.width - 2 * s, size.height - 2 * s), style = Stroke(s, cap = StrokeCap.Round))
    drawLine(c, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.78f, size.height * 0.1f), s, StrokeCap.Round)
    drawLine(c, Offset(size.width * 0.78f, size.height * 0.1f), Offset(size.width * 0.66f, size.height * 0.36f), s, StrokeCap.Round)
}

@Composable
private fun Segmented(tab: Int, onChange: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(Btn).padding(3.dp)) {
        listOf("NUANCE", "MATERIAL").forEachIndexed { i, t ->
            Box(
                Modifier.clip(shape).background(if (tab == i) Color(0xFF3A3A3A) else Color.Transparent)
                    .tap { if (tab != i) onChange(i) }.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    t, color = if (tab == i) StyleYellow else Color.White, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                )
            }
        }
    }
}

// ---- Nuance ---------------------------------------------------------------------------------------

@Composable
private fun NuanceTab(d: StyleState, h: Haptics) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(112.dp).pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = { acc = 0f }) { _, dx ->
                    acc += dx
                    if (acc < -70f && d.base < BaseStyles.lastIndex) {
                        d.base++; d.resetNuance(); h.click(); acc = 0f
                    } else if (acc > 70f && d.base > 0) {
                        d.base--; d.resetNuance(); h.click(); acc = 0f
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            BaseStyles.indices.reversed().forEach { i ->
                val rel = i - d.base
                val ox by animateDpAsState(if (rel < 0) (-80).dp else (rel * 32).dp, spring(0.8f, Spring.StiffnessMediumLow), label = "ox")
                val sc by animateFloatAsState(if (rel < 0) 0.8f else (1f - rel * 0.1f).coerceAtLeast(0.55f), spring(0.8f), label = "sc")
                val al by animateFloatAsState(if (rel < 0) 0f else (1f - rel * 0.22f).coerceAtLeast(0.2f), label = "al")
                Box(
                    Modifier.offset(x = ox).zIndex(-abs(rel).toFloat())
                        .graphicsLayer { scaleX = sc; scaleY = sc; alpha = al }
                ) {
                    DotPad(
                        BaseStyles[i].tint, if (rel == 0) d.x else 0f, if (rel == 0) d.y else 0f, rel == 0,
                        { nx, ny -> d.x = nx; d.y = ny }, h,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        PillSlider(d.intensity, { d.intensity = it }, h)
    }
}

@Composable
private fun DotPad(tint: Color, x: Float, y: Float, interactive: Boolean, onChange: (Float, Float) -> Unit, h: Haptics) {
    val shape = RoundedCornerShape(26.dp)
    val change by rememberUpdatedState(onChange)
    var lastCell by remember { mutableStateOf(Pair(0, 0)) }
    val inputs = if (!interactive) Modifier else Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { o -> setPad(o, size.width.toFloat(), size.height.toFloat(), 14.dp.toPx()) { nx, ny -> change(nx, ny) } },
                onDoubleTap = { change(0f, 0f); h.click() },
            )
        }
        .pointerInput(Unit) {
            val inset = 14.dp.toPx()
            fun upd(o: Offset) = setPad(o, size.width.toFloat(), size.height.toFloat(), inset) { nx, ny ->
                val cell = Pair((nx * 5).roundToInt(), (ny * 5).roundToInt())
                if (cell != lastCell) { lastCell = cell; if (cell == Pair(0, 0)) h.click() else h.tick() }
                change(nx, ny)
            }
            detectDragGestures(onDragStart = { upd(it) }) { c, _ -> c.consume(); upd(c.position) }
        }

    Canvas(Modifier.size(98.dp).shadow(18.dp, shape, ambientColor = tint, spotColor = tint).clip(shape).then(inputs)) {
        val w = size.width
        val inset = 14.dp.toPx()
        drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.65f), Color(0xFF3A3532)), Offset(w * 0.5f, w * 0.55f), w * 0.75f))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)))
        val n = 11
        val span = w - inset * 2
        val px = w / 2 + x * (w / 2 - inset)
        val py = w / 2 - y * (w / 2 - inset)
        val sigma = w * 0.16f
        for (i in 0 until n) for (j in 0 until n) {
            var gx = inset + span * i / (n - 1)
            var gy = inset + span * j / (n - 1)
            val dx = gx - px
            val dy = gy - py
            val d = max(sqrt(dx * dx + dy * dy), 1f)
            val k = if (interactive) exp(-(d * d) / (2 * sigma * sigma)) else 0f
            gx += dx / d * 7.dp.toPx() * k
            gy += dy / d * 7.dp.toPx() * k
            drawCircle(Color.White.copy(alpha = 0.55f + 0.4f * k), 1.1.dp.toPx() * (1f + 1.5f * k), Offset(gx, gy))
        }
        if (interactive) {
            drawCircle(Color.White.copy(alpha = 0.25f), 12.dp.toPx(), Offset(px, py))
            drawCircle(Color.White, 6.dp.toPx(), Offset(px, py))
        }
    }
}

private fun setPad(o: Offset, w: Float, hgt: Float, inset: Float, out: (Float, Float) -> Unit) {
    val nx = ((o.x - w / 2f) / (w / 2f - inset)).coerceIn(-1f, 1f)
    val ny = (-(o.y - hgt / 2f) / (hgt / 2f - inset)).coerceIn(-1f, 1f)
    out(nx, ny)
}

@Composable
private fun PillSlider(value: Float, onChange: (Float) -> Unit, h: Haptics) {
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier.width(150.dp).height(28.dp)
            .pointerInput(Unit) {
                fun upd(x: Float) {
                    val q = ((x / size.width).coerceIn(0f, 1f) * 20).roundToInt() / 20f
                    if (q != cur) { change(q); h.tick() }
                }
                detectTapGestures(onTap = { upd(it.x) })
            }
            .pointerInput(Unit) {
                fun upd(x: Float) {
                    val q = ((x / size.width).coerceIn(0f, 1f) * 20).roundToInt() / 20f
                    if (q != cur) { change(q); h.tick() }
                }
                detectHorizontalDragGestures(onDragStart = { upd(it.x) }) { c, _ -> c.consume(); upd(c.position.x) }
            }
    ) {
        val cy = size.height / 2
        drawLine(Color(0xFF3A3A3A), Offset(0f, cy), Offset(size.width, cy), 4.dp.toPx(), StrokeCap.Round)
        val tw = 38.dp.toPx(); val th = 16.dp.toPx()
        val tx = (size.width - tw) * value
        drawRoundRect(
            Color(0xFFBDBDBD), Offset(tx, cy - th / 2), Size(tw, th),
            androidx.compose.ui.geometry.CornerRadius(th / 2, th / 2),
        )
    }
}

// ---- Material -------------------------------------------------------------------------------------

private val MatLabels = listOf("STANDARD", "SOFT SKIN", "FILM", "GRAIN")

private fun level(d: StyleState, i: Int) = when (i) { 1 -> d.soft; 2 -> d.film; 3 -> d.grain; else -> 0f }
private fun setLevel(d: StyleState, i: Int, v: Float) {
    when (i) { 1 -> d.soft = v; 2 -> d.film = v; 3 -> d.grain = v }
}

@Composable
private fun MaterialTab(d: StyleState, h: Haptics) {
    var open by remember { mutableStateOf<Int?>(null) }
    val o = open
    if (o == null) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for (i in 0..3) {
                if (i == 3) Box(Modifier.padding(bottom = 22.dp).width(1.dp).height(30.dp).background(Color(0xFF3A3A3A)))
                val selected = if (i == 0) (d.soft == 0f && d.film == 0f && d.grain == 0f) else level(d, i) > 0f
                MatButton(i, selected, level(d, i)) {
                    if (i == 0) {
                        d.soft = 0f; d.film = 0f; d.grain = 0f; h.double()
                    } else {
                        if (level(d, i) == 0f) setLevel(d, i, 0.55f)
                        h.click(); open = i
                    }
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MatButton(o, true, 0f, showTicks = false) { h.click(); open = null }
            TickSlider(level(d, o), { setLevel(d, o, it) }, h, Modifier.weight(1f))
            Text("${(level(d, o) * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MatButton(i: Int, selected: Boolean, lvl: Float, showTicks: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tap(onClick)) {
        if (showTicks && i != 0) Row(Modifier.height(14.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            val on = (lvl * 8).roundToInt()
            if (lvl > 0f) for (t in 0 until 8) Box(
                Modifier.width(2.dp).height(6.dp).background(if (t < on) StyleYellow else Color(0xFF3C3C3C))
            )
        } else if (showTicks) Spacer(Modifier.height(14.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (selected) Color(0xFFF2F2F2) else Btn),
            contentAlignment = Alignment.Center,
        ) { MatIcon(i, if (selected) Color(0xFF111111) else Color.White) }
        Text(
            MatLabels[i], Modifier.padding(top = 6.dp),
            color = if (selected) Color.White else Grey, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun MatIcon(kind: Int, c: Color) = Canvas(Modifier.size(22.dp)) {
    val s = size.width
    val sw = 1.8.dp.toPx()
    when (kind) {
        0 -> {
            drawCircle(c, s * 0.42f, style = Stroke(sw))
            drawLine(c, Offset(s * 0.22f, s * 0.78f), Offset(s * 0.78f, s * 0.22f), sw, StrokeCap.Round)
        }
        1 -> {
            drawCircle(c, s * 0.2f, Offset(s / 2, s * 0.32f))
            drawArc(c, 180f, 180f, true, Offset(s * 0.16f, s * 0.55f), Size(s * 0.68f, s * 0.8f))
        }
        2 -> {
            drawRoundRect(c, Offset(s * 0.1f, s * 0.14f), Size(s * 0.8f, s * 0.72f), androidx.compose.ui.geometry.CornerRadius(s * 0.1f), style = Stroke(sw))
            for (k in 0..2) {
                val yy = s * (0.24f + 0.2f * k)
                drawRect(c, Offset(s * 0.17f, yy), Size(s * 0.08f, s * 0.1f))
                drawRect(c, Offset(s * 0.75f, yy), Size(s * 0.08f, s * 0.1f))
            }
            drawRect(c, Offset(s * 0.36f, s * 0.3f), Size(s * 0.28f, s * 0.4f), style = Stroke(sw * 0.8f))
        }
        else -> {
            val n = 5
            for (i in 0 until n) for (j in 0 until n) {
                val px = s * (0.12f + 0.76f * i / (n - 1))
                val py = s * (0.12f + 0.76f * j / (n - 1))
                val dx = px - s / 2; val dy = py - s / 2
                if (sqrt(dx * dx + dy * dy) <= s * 0.44f) drawCircle(c.copy(alpha = 0.85f), s * 0.055f, Offset(px, py))
            }
        }
    }
}

@Composable
private fun TickSlider(value: Float, onChange: (Float) -> Unit, h: Haptics, modifier: Modifier) {
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.height(36.dp)
            .pointerInput(Unit) {
                fun upd(x: Float) {
                    val q = ((x / size.width).coerceIn(0f, 1f) * 40).roundToInt() / 40f
                    if (q != cur) { change(q); h.tick() }
                }
                detectTapGestures(onTap = { upd(it.x) })
            }
            .pointerInput(Unit) {
                fun upd(x: Float) {
                    val q = ((x / size.width).coerceIn(0f, 1f) * 40).roundToInt() / 40f
                    if (q != cur) { change(q); h.tick() }
                }
                detectHorizontalDragGestures(onDragStart = { upd(it.x) }) { c, _ -> c.consume(); upd(c.position.x) }
            }
    ) {
        val n = 40
        val gap = size.width / (n - 1)
        val cy = size.height / 2
        for (i in 0 until n) {
            val on = value > 0f && i / (n - 1f) <= value + 0.001f
            val th = if (i % 5 == 0) 20.dp.toPx() else 13.dp.toPx()
            drawLine(
                if (on) StyleYellow else Color(0xFF3C3C3C),
                Offset(i * gap, cy - th / 2), Offset(i * gap, cy + th / 2), 2.dp.toPx(), StrokeCap.Round,
            )
        }
    }
}
