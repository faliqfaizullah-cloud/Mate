package com.mate.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private val SheetBg = Color(0xF21C1C1E)
private val Muted = Color(0xFF8E8E93)

@Composable
fun ToolsSheet(
    s: CamSettings,
    videoMode: Boolean,
    recording: Boolean,
    style: ShutterStyle,
    onStyle: (ShutterStyle) -> Unit,
    onFlip: () -> Unit,
    h: Haptics,
    modifier: Modifier,
) {
    val tabs = listOf("Filters", "Exposure", "Grid", "More")
    Column(
        modifier.clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(SheetBg)
            .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 18.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                .clip(RoundedCornerShape(50)).background(Color(0xFF48484A))
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            tabs.forEachIndexed { i, t ->
                Text(
                    t,
                    color = if (s.tab == i) Color.White else Muted,
                    fontSize = 16.sp,
                    fontWeight = if (s.tab == i) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.tap { s.tab = i; h.tick() },
                )
            }
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            when (s.tab) {
                0 -> FiltersTab(s, videoMode, h)
                1 -> ExposureTab(s, h)
                2 -> GridTab(s, h)
                else -> MoreTab(s, videoMode, recording, style, onStyle, onFlip, h)
            }
        }
    }
}

@Composable
private fun FiltersTab(s: CamSettings, videoMode: Boolean, h: Haptics) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Filters.forEachIndexed { i, f ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tap { s.filter = i; h.tick() }) {
                val shape = RoundedCornerShape(16.dp)
                Canvas(
                    Modifier.size(64.dp).clip(shape)
                        .border(2.dp, if (s.filter == i) Mint else Color.Transparent, shape)
                ) {
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color(0xFFFF5F6D), Color(0xFFFFC371), Color(0xFF47E891), Color(0xFF3A7BD5))
                        ),
                        colorFilter = f.matrix?.let { ColorFilter.colorMatrix(ComposeColorMatrix(it.array)) },
                    )
                }
                Text(
                    f.name, Modifier.padding(top = 6.dp),
                    color = if (s.filter == i) Mint else Color.White, fontSize = 12.sp,
                )
            }
        }
    }
    if (videoMode) Text("Filters are baked into photos. Video records unfiltered.", Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp)
}

@Composable
private fun ExposureTab(s: CamSettings, h: Haptics) {
    Label("Mode")
    ChipRow {
        Chip("Auto", !s.manual) { s.manual = false; h.click() }
        Chip("Manual", s.manual) { if (s.manualOk) { s.manual = true; h.click() } else h.double() }
    }
    if (!s.manualOk) Text("Manual ISO/shutter isn't supported on this camera.", Modifier.padding(top = 6.dp), color = Muted, fontSize = 12.sp)

    if (!s.manual) {
        if (s.evMax > s.evMin) {
            Label("Exposure   ${"%+.1f".format(s.ev * s.evStep)} EV")
            Slider(
                value = s.ev.toFloat(),
                onValueChange = { v ->
                    val n = v.roundToInt()
                    if (n != s.ev) { s.ev = n; h.tick() }
                },
                valueRange = s.evMin.toFloat()..s.evMax.toFloat(),
                steps = (s.evMax - s.evMin - 1).coerceAtLeast(0),
                colors = sliderColors(),
            )
        }
    } else {
        Label("ISO   ${s.iso}")
        if (s.isoMax > s.isoMin) Slider(
            value = s.iso.toFloat(),
            onValueChange = { v ->
                val n = ((v / 50f).roundToInt() * 50).coerceIn(s.isoMin, s.isoMax)
                if (n != s.iso) { s.iso = n; h.tick() }
            },
            valueRange = s.isoMin.toFloat()..s.isoMax.toFloat(),
            colors = sliderColors(),
        )
        Label("Shutter   1/${s.shutter} s")
        ChipRow {
            ShutterSpeeds.forEach { d -> Chip("1/$d", s.shutter == d) { s.shutter = d; h.tick() } }
        }
    }
    Label("White balance")
    ChipRow { Wb.entries.forEach { w -> Chip(w.label, s.wb == w) { s.wb = w; h.tick() } } }
}

@Composable
private fun GridTab(s: CamSettings, h: Haptics) {
    Label("Composition guide")
    ChipRow { GridType.entries.forEach { g -> Chip(g.label, s.grid == g) { s.grid = g; h.tick() } } }
}

@Composable
private fun MoreTab(
    s: CamSettings, videoMode: Boolean, recording: Boolean, style: ShutterStyle,
    onStyle: (ShutterStyle) -> Unit, onFlip: () -> Unit, h: Haptics,
) {
    Label(if (videoMode) "Torch" else "Flash")
    ChipRow { FlashOpt.entries.forEach { f -> Chip(f.label, s.flash == f) { s.flash = f; h.tick() } } }
    Label("Timer")
    ChipRow { listOf(0, 3, 10).forEach { t -> Chip(if (t == 0) "Off" else "${t}s", s.timer == t) { s.timer = t; h.tick() } } }
    Label("Video quality")
    ChipRow { VideoQ.entries.forEach { q -> Chip(q.label, s.quality == q) { if (!recording) { s.quality = q; h.tick() } else h.double() } } }
    Label("Shutter style")
    ChipRow { ShutterStyle.entries.forEach { st -> Chip(st.name.lowercase().replaceFirstChar { it.uppercase() }, style == st) { onStyle(st) } } }
    Label("Camera")
    ChipRow { Chip("Flip camera", false) { onFlip() } }
}

@Composable
fun Label(text: String) =
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
fun ChipRow(content: @Composable () -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Mint else Color(0xFF2C2C2E))
            .tap(onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Mint, activeTrackColor = Mint, inactiveTrackColor = Color(0xFF3A3A3C),
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
)

/** Glass segmented control: PHOTO | VIDEO */
@Composable
fun ModePill(video: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Row(modifier.clip(shape).background(Color.Black.copy(alpha = 0.42f)).padding(4.dp)) {
        listOf("PHOTO" to false, "VIDEO" to true).forEach { (label, isVideo) ->
            val sel = video == isVideo
            Box(
                Modifier.clip(shape).background(if (sel) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                    .tap { if (!sel) onChange(isVideo) }.padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(label, color = if (sel) Color.White else Color(0xFFBBBBBB), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun RecPill(seconds: Int, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        Text("%02d:%02d".format(seconds / 60, seconds % 60), Modifier.padding(start = 8.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
