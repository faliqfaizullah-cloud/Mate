package com.mate.camera

import android.content.Context
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Placeholders = listOf(
    listOf(Color(0xFFF7A8C4), Color(0xFF9BD14B)),
    listOf(Color(0xFF7CC47A), Color(0xFFE9F0DA)),
    listOf(Color(0xFFF2F2F2), Color(0xFFB9B9B9)),
)

@Composable
fun CollectionScreen(photos: List<Uri>, onClose: () -> Unit, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val h = remember(view) { Haptics(ctx, view) }
    val prefs = remember { ctx.getSharedPreferences("mate", Context.MODE_PRIVATE) }

    var expanded by remember { mutableStateOf(false) }
    var byViews by remember { mutableStateOf(false) }
    var viewsTick by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var order by remember(photos) { mutableStateOf(photos) }

    fun views(u: Uri) = prefs.getInt("v_$u", 0)
    fun bump(idx: Int) {
        val u = order.getOrNull(idx) ?: return
        prefs.edit().putInt("v_$u", views(u) + 1).apply()
        order = listOf(u) + order.filterNot { it == u }
        viewsTick++
        h.tick()
        viewer = photos.indexOf(u).takeIf { it >= 0 }
    }

    val shown = if (byViews) order.sortedByDescending { views(it) } else order
    val top = photos.maxByOrNull { views(it) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val dockH = 120.dp
        val sheetH by animateDpAsState(
            if (expanded) 120.dp else maxHeight - dockH,
            spring(0.85f, Spring.StiffnessLow), label = "sheet",
        )
        val lightAlpha by animateFloatAsState(if (expanded) 0f else 1f, label = "a")

        Column(Modifier.fillMaxSize()) {
            // ---- white sheet --------------------------------------------------
            Box(
                Modifier.fillMaxWidth().height(sheetH)
                    .clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))
                    .background(Color.White)
            ) {
                // strip shown when the sheet is pushed up
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - lightAlpha }) {
                    ThumbOrPlaceholder(top, 0, Modifier.fillMaxSize(), ContentScale.Crop, 900, 500)
                }
                if (lightAlpha > 0.01f) Column(
                    Modifier.fillMaxSize().graphicsLayer { alpha = lightAlpha }
                        .statusBarsPadding().padding(horizontal = 28.dp)
                ) {
                    // dotted background
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        RoundGlyph(Sky, { h.click(); onClose() }) { ArrowGlyph() }
                        RoundGlyph(Sky, { h.click(); byViews = !byViews }) { SearchGlyph() }
                    }
                    Text(
                        "Your collection", Modifier.padding(top = 26.dp),
                        color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                    )
                    Text("${photos.size} images", color = Color(0xFF8E8E93), fontSize = 17.sp)

                    Fan(shown, Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) { bump(order.indexOf(shown[it])) }

                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Most viewed", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("${top?.let { views(it) } ?: 0} views", color = Color(0xFF8E8E93), fontSize = 15.sp)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .tap { top?.let { u -> h.click(); viewer = photos.indexOf(u).takeIf { it >= 0 } } }
                    ) {
                        ThumbOrPlaceholder(top, 1, Modifier.fillMaxSize(), ContentScale.Crop, 1000, 600)
                    }
                }
            }

            // ---- dark dock / expanded area -------------------------------------
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -40f && !expanded) { expanded = true; h.click() }
                            else if (total > 40f && expanded) { expanded = false; h.click() }
                        },
                    ) { _, dy -> total += dy }
                }
            ) {
                Crossfade(expanded, Modifier.fillMaxSize(), label = "dark") { exp ->
                    if (!exp) DockRow(photos, onAdd = { h.confirm(); onClose() })
                    else Column(
                        Modifier.fillMaxSize().padding(horizontal = 28.dp).navigationBarsPadding()
                            .padding(top = 28.dp, bottom = 16.dp)
                    ) {
                        Text("Camera roll", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                        Text("${photos.size} shots saved", color = Color(0xFF8E8E93), fontSize = 17.sp)
                        Fan(shown, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) { bump(order.indexOf(shown[it])) }
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50))
                                .background(Color(0xFF262626)).tap { h.confirm(); onClose() },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlusGlyph(Color.White); Spacer(Modifier.width(10.dp))
                            Text("Shoot", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                // grab handle
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xFF3A3A3A))
                        .tap { expanded = !expanded; h.click() }
                )
            }
        }

        viewer?.let { idx ->
            PhotoViewer(photos, idx, onClose = { viewer = null }, onChanged = onRefresh)
        }
    }
}

@Composable
private fun DockRow(photos: List<Uri>, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp, 52.dp)) {
            ThumbOrPlaceholder(photos.getOrNull(1), 0, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).graphicsLayer { rotationZ = -8f }, ContentScale.Crop, 150, 150)
            ThumbOrPlaceholder(photos.getOrNull(0), 1, Modifier.offset(18.dp, 2.dp).size(46.dp).clip(RoundedCornerShape(8.dp)).graphicsLayer { rotationZ = 6f }, ContentScale.Crop, 150, 150)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Camera roll", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("${photos.size} shots saved", color = Color(0xFF8E8E93), fontSize = 16.sp)
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).tap(onAdd),
            contentAlignment = Alignment.Center,
        ) { PlusGlyph(Color.White) }
    }
}

/** Three overlapping, slightly rotated cards – front card is index 0. */
@Composable
private fun Fan(photos: List<Uri>, modifier: Modifier, onTapCard: (Int) -> Unit) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val ch = minOf(maxHeight * 0.9f, 290.dp)
        val cw = ch * 0.8f
        val y = (maxHeight - ch) / 2
        data class Slot(val idx: Int, val x: Dp, val dy: Dp, val rot: Float)
        val slots = listOf(
            Slot(1, -cw * 0.38f, 14.dp, -5f),
            Slot(2, maxWidth - cw * 0.62f, (-8).dp, 4f),
            Slot(0, (maxWidth - cw) / 2 + 8.dp, 0.dp, 3f),
        )
        slots.forEach { s ->
            Box(
                Modifier.offset(s.x, y + s.dy).size(cw, ch)
                    .graphicsLayer {
                        rotationZ = s.rot
                        shadowElevation = 14.dp.toPx()
                        shape = RoundedCornerShape(6.dp)
                        clip = true
                    }
                    .tap { if (photos.getOrNull(s.idx) != null) onTapCard(s.idx) }
            ) { ThumbOrPlaceholder(photos.getOrNull(s.idx), s.idx, Modifier.fillMaxSize(), ContentScale.Crop, 600, 750) }
        }
    }
}

@Composable
private fun ThumbOrPlaceholder(uri: Uri?, slot: Int, modifier: Modifier, scale: ContentScale, w: Int, h: Int) {
    val bmp by rememberThumb(uri, w, h)
    val colors = Placeholders[slot % Placeholders.size]
    Box(modifier.background(Brush.linearGradient(colors))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) }
    }
}

@Composable
private fun RoundGlyph(bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(bg).tap(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ArrowGlyph() = Canvas(Modifier.size(16.dp)) {
    val s = 2.dp.toPx()
    drawLine(Color.White, Offset(size.width, size.height / 2), Offset(0f, size.height / 2), s, StrokeCap.Round)
    drawLine(Color.White, Offset(size.width * 0.45f, size.height * 0.1f), Offset(0f, size.height / 2), s, StrokeCap.Round)
    drawLine(Color.White, Offset(size.width * 0.45f, size.height * 0.9f), Offset(0f, size.height / 2), s, StrokeCap.Round)
}

@Composable
private fun SearchGlyph() = Canvas(Modifier.size(16.dp)) {
    val s = 2.dp.toPx()
    drawCircle(Color.White, size.width * 0.36f, Offset(size.width * 0.42f, size.height * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
    drawLine(Color.White, Offset(size.width * 0.68f, size.height * 0.68f), Offset(size.width * 0.95f, size.height * 0.95f), s, StrokeCap.Round)
}

@Composable
private fun PlusGlyph(color: Color) = Canvas(Modifier.size(16.dp)) {
    val s = 2.dp.toPx()
    drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), s, StrokeCap.Round)
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), s, StrokeCap.Round)
}
