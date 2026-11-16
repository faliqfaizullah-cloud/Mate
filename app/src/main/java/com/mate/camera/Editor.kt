package com.mate.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

enum class Aspect(val label: String, val ratio: Float?) {
    Original("Original", null),
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    ThreeFour("3:4", 3f / 4f),
    SixteenNine("16:9", 16f / 9f),
    NineSixteen("9:16", 9f / 16f),
}

private val Panel = Color(0xFF1C1C1E)
private val Dim = Color(0xFF8E8E93)

private fun buildMatrix(filter: Int, bright: Float, con: Float, sat: Float, warm: Float): ColorMatrix {
    val m = Filters[filter].matrix?.let { ColorMatrix(it) } ?: ColorMatrix()
    m.postConcat(ColorMatrix().apply { setSaturation(sat) })
    m.postConcat(contrast(con))
    val t = bright * 80f
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, t,
                0f, 1f, 0f, 0f, t,
                0f, 0f, 1f, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    val r = 1f + warm * 0.15f
    val b = 1f - warm * 0.15f
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    return m
}

private fun transform(src: Bitmap, quarterTurns: Int, flip: Boolean): Bitmap {
    val m = Matrix()
    if (flip) m.postScale(-1f, 1f)
    if (quarterTurns % 4 != 0) m.postRotate(90f * (quarterTurns % 4))
    return if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun cropTo(src: Bitmap, ratio: Float?): Bitmap {
    if (ratio == null) return src
    val cur = src.width.toFloat() / src.height
    return if (cur > ratio) {
        val nw = (src.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(src, (src.width - nw) / 2, 0, nw, src.height)
    } else {
        val nh = (src.width / ratio).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(src, 0, (src.height - nh) / 2, src.width, nh)
    }
}

@Composable
fun EditorScreen(uri: Uri, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val h = remember(view) { Haptics(ctx, view) }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    val base by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodeBitmap(ctx, uri, 1600) }.getOrNull() }
    }
    var filter by remember { mutableIntStateOf(0) }
    var bright by remember { mutableFloatStateOf(0f) }
    var con by remember { mutableFloatStateOf(1f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var warm by remember { mutableFloatStateOf(0f) }
    var turns by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(Aspect.Original) }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    val prev = remember(base, turns, flip) { base?.let { transform(it, turns, flip) } }
    val matrix = buildMatrix(filter, bright, con, sat, warm)
    val changed = filter != 0 || bright != 0f || con != 1f || sat != 1f || warm != 0f ||
        turns % 4 != 0 || flip || aspect != Aspect.Original

    fun reset() {
        filter = 0; bright = 0f; con = 1f; sat = 1f; warm = 0f; turns = 0; flip = false; aspect = Aspect.Original
        h.click()
    }

    fun save() {
        if (saving) return
        saving = true
        h.click()
        val m = buildMatrix(filter, bright, con, sat, warm)
        val t = turns; val f = flip; val ratio = aspect.ratio
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val full = decodeBitmap(ctx, uri, 4096)
                    val b = cropTo(transform(full, t, f), ratio)
                    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                    Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MATE_EDIT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mate")
                    }
                    val dst = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    ctx.contentResolver.openOutputStream(dst)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                }.isSuccess
            }
            saving = false
            if (ok) { h.confirm(); onSaved() } else h.double()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Cancel", Color.White.copy(alpha = 0.16f), Color.White) { h.click(); onClose() }
            if (changed) Pill("Reset", Color.Transparent, Dim) { reset() }
            Pill(if (saving) "Saving…" else "Save copy", Mint, Color.Black) { save() }
        }

        // preview (press and hold to compare with the original)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { showOriginal = true; h.tick(); tryAwaitRelease(); showOriginal = false })
                },
            contentAlignment = Alignment.Center,
        ) {
            prev?.let { bmp ->
                val cf = if (showOriginal) null else ColorFilter.colorMatrix(ComposeColorMatrix(matrix.array))
                val r = aspect.ratio
                if (r == null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = cf)
                else Box(Modifier.aspectRatio(r).clip(RoundedCornerShape(6.dp))) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = cf)
                }
            }
            if (showOriginal) Text("Original", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), color = Color.White, fontSize = 13.sp)
        }

        // tool panel
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(Panel)
                .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Filters", "Adjust", "Crop").forEachIndexed { i, t ->
                    Text(
                        t, color = if (tab == i) Color.White else Dim, fontSize = 16.sp,
                        fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.tap { tab = i; h.tick() },
                    )
                }
            }
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Filters.forEachIndexed { i, f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tap { filter = i; h.tick() }) {
                                val shape = RoundedCornerShape(16.dp)
                                Box(Modifier.size(68.dp).clip(shape).border(2.dp, if (filter == i) Mint else Color.Transparent, shape)) {
                                    prev?.let {
                                        Image(
                                            it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                            colorFilter = f.matrix?.let { m -> ColorFilter.colorMatrix(ComposeColorMatrix(m.array)) },
                                        )
                                    }
                                }
                                Text(f.name, Modifier.padding(top = 6.dp), color = if (filter == i) Mint else Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    1 -> Column {
                        Adjust("Brightness", bright, -1f..1f, "%+d".format((bright * 100).roundToInt()), h) { bright = it }
                        Adjust("Contrast", con, 0.5f..1.5f, "%d".format(((con - 1f) * 200).roundToInt()), h) { con = it }
                        Adjust("Saturation", sat, 0f..2f, "%d".format(((sat - 1f) * 100).roundToInt()), h) { sat = it }
                        Adjust("Warmth", warm, -1f..1f, "%+d".format((warm * 100).roundToInt()), h) { warm = it }
                    }

                    else -> Column {
                        Label("Orientation")
                        ChipRow {
                            Chip("Rotate 90°", false) { turns = (turns + 1) % 4; h.click() }
                            Chip("Flip", flip) { flip = !flip; h.click() }
                        }
                        Label("Aspect ratio")
                        ChipRow { Aspect.entries.forEach { a -> Chip(a.label, aspect == a) { aspect = a; h.tick() } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).tap(onClick).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Adjust(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String,
    h: Haptics, onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(shown, color = Color.White, fontSize = 13.sp)
    }
    Slider(
        value = value,
        onValueChange = { v ->
            val n = (v * 20f).roundToInt() / 20f
            if (n != value) { onChange(n); h.tick() }
        },
        valueRange = range,
        colors = sliderColors(),
    )
}
