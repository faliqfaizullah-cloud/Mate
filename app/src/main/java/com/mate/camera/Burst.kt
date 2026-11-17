package com.mate.camera

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
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

/** Pick your favourite frames from a burst, then keep everything or delete the rest. */
@Composable
fun BurstPicker(frames: List<Uri>, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val h = remember(view) { Haptics(ctx, view) }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    var index by remember { mutableIntStateOf(0) }
    val picks = remember { mutableStateListOf<Uri>() }
    val listState = rememberLazyListState()
    var confirm by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(index) {
        listState.animateScrollToItem(index)
        val b = withContext(Dispatchers.IO) {
            runCatching { decodeBitmap(ctx, frames[index], 1600).asImageBitmap() }.getOrNull()
        }
        if (b != null) shown = b
    }

    fun toggle(u: Uri) {
        if (u in picks) picks.remove(u) else picks.add(u)
        h.click()
    }

    fun deleteOthers() {
        scope.launch {
            withContext(Dispatchers.IO) {
                frames.filter { it !in picks }.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
            }
            h.confirm()
            onChanged()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Cancel", Color.White.copy(alpha = 0.16f), Color.White) { h.click(); onClose() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${index + 1} / ${frames.size}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (picks.isEmpty()) "Pick your favourites" else "${picks.size} selected",
                        color = if (picks.isEmpty()) Color(0xFF8E8E93) else Sky, fontSize = 12.sp,
                    )
                }
                Pill("Done", if (picks.isEmpty()) Color(0xFF2C2C2E) else Mint, if (picks.isEmpty()) Color(0xFF8E8E93) else Color.Black) {
                    if (picks.isEmpty()) h.double() else { h.click(); confirm = true }
                }
            }

            // big preview: swipe to scrub through frames, double-tap to pick
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { toggle(frames[index]) })
                    }
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectHorizontalDragGestures(onDragStart = { acc = 0f }) { c, dx ->
                            c.consume()
                            acc += dx
                            if (acc < -50f && index < frames.lastIndex) { index++; acc = 0f; h.tick() }
                            else if (acc > 50f && index > 0) { index--; acc = 0f; h.tick() }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                shown?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                val picked = frames[index] in picks
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(46.dp).clip(CircleShape)
                        .background(if (picked) Sky else Color.Black.copy(alpha = 0.5f))
                        .border(2.dp, Color.White, CircleShape).tap { toggle(frames[index]) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Canvas(Modifier.size(18.dp)) {
                        val s = 2.6.dp.toPx()
                        drawLine(Color.White, Offset(0f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.92f), s, StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width * 0.38f, size.height * 0.92f), Offset(size.width, size.height * 0.1f), s, StrokeCap.Round)
                    }
                }
            }

            // filmstrip
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(frames) { i, u ->
                    val bmp by rememberThumb(u, 180, 240)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(58.dp, 80.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222))
                                .border(if (i == index) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                                .tap { index = i; h.tick() }
                        ) {
                            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Box(
                            Modifier.padding(top = 8.dp).size(10.dp).clip(CircleShape)
                                .background(if (u in picks) Sky else Color.Transparent)
                                .border(1.5.dp, if (u in picks) Sky else Color(0xFF555555), CircleShape)
                                .tap { toggle(u) }
                        )
                    }
                }
            }
        }

        if (confirm) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)).tap { confirm = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(32.dp).acrylic(RoundedCornerShape(26.dp), Color(0xFF1C1C1E), 0.82f).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Keep ${picks.size} favourite${if (picks.size > 1) "s" else ""}?",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${frames.size - picks.size} other frame${if (frames.size - picks.size != 1) "s" else ""} can be deleted to free space.",
                    Modifier.padding(top = 6.dp, bottom = 18.dp), color = Color(0xFF8E8E93), fontSize = 13.sp,
                )
                Pill("Keep favourites only", Color(0xFFFF3B30), Color.White) { confirm = false; deleteOthers() }
                Box(Modifier.padding(top = 10.dp)) {
                    Pill("Keep all frames", Color(0xFF2C2C2E), Color.White) { h.click(); onClose() }
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).tap(onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
