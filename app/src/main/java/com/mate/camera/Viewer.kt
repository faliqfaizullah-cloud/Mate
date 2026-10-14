package com.mate.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Decodes with EXIF rotation applied, capped to [maxSide] px on the long edge. */
fun decodeBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val big = max(info.size.width, info.size.height)
        if (big > maxSide) {
            val r = maxSide.toFloat() / big
            decoder.setTargetSize(
                (info.size.width * r).toInt().coerceAtLeast(1),
                (info.size.height * r).toInt().coerceAtLeast(1),
            )
        }
    }
}

private fun isVideo(ctx: Context, uri: Uri) = ctx.contentResolver.getType(uri)?.startsWith("video") == true

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(photos: List<Uri>, startIndex: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val h = remember(view) { Haptics(ctx, view) }
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Uri?>(null) }

    BackHandler { onClose() }
    LaunchedEffect(pager.currentPage) { zoomed = false; h.tick() }

    val current = photos.getOrNull(pager.currentPage)
    val currentIsVideo = current != null && isVideo(ctx, current)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp, userScrollEnabled = !zoomed,
        ) { page ->
            val uri = photos[page]
            ZoomPage(
                uri, isCurrent = pager.currentPage == page, h = h,
                onZoom = { zoomed = it }, onTap = { chrome = !chrome },
                onPlay = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                },
            )
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), fadeIn(), fadeOut()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                        .tap { h.click(); onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val s = 2.dp.toPx()
                        drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), s, StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
                    }
                }
                Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                GlassPill("Share") {
                    h.click()
                    current?.let { u ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = if (currentIsVideo) "video/*" else "image/*"
                            putExtra(Intent.EXTRA_STREAM, u)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share"))
                    }
                }
            }
        }

        AnimatedVisibility(
            chrome && !zoomed && !currentIsVideo, Modifier.align(Alignment.BottomCenter), fadeIn(), fadeOut()
        ) {
            Box(Modifier.navigationBarsPadding().padding(bottom = 28.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Mint)
                        .tap { h.click(); editing = current }.padding(horizontal = 44.dp, vertical = 15.dp)
                ) { Text("Edit", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        editing?.let { u ->
            EditorScreen(
                u,
                onClose = { editing = null },
                onSaved = { editing = null; onChanged(); scope.launch { pager.scrollToPage(0) } },
            )
        }
    }
}

@Composable
private fun GlassPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
            .tap(onClick).padding(horizontal = 18.dp, vertical = 10.dp)
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun ZoomPage(
    uri: Uri, isCurrent: Boolean, h: Haptics,
    onZoom: (Boolean) -> Unit, onTap: () -> Unit, onPlay: () -> Unit,
) {
    val ctx = LocalContext.current
    val video = remember(uri) { isVideo(ctx, uri) }
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (video) ctx.contentResolver.loadThumbnail(uri, Size(1080, 1920), null).asImageBitmap()
                else decodeBitmap(ctx, uri, 2560).asImageBitmap()
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isCurrent) { if (!isCurrent) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale) { if (isCurrent) onZoom(scale > 1.02f) }

    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!video) {
                            scale = if (scale > 1.05f) 1f else 2.5f
                            offset = Offset.Zero
                            h.tick()
                        }
                    },
                    onTap = { onTap() },
                )
            }
            .pointerInput(Unit) {
                // custom transform: only consume when pinching or already zoomed, so the pager can still swipe
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled && !video) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            if (event.changes.size > 1 || scale > 1.01f) {
                                val ns = (scale * z).coerceIn(1f, 6f)
                                val mx = size.width * (ns - 1f) / 2f
                                val my = size.height * (ns - 1f) / 2f
                                offset = if (ns <= 1.01f) Offset.Zero
                                else Offset((offset.x + p.x).coerceIn(-mx, mx), (offset.y + p.y).coerceIn(-my, my))
                                scale = ns
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
        }
        if (video) Box(
            Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).tap { h.click(); onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(26.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close()
                }
                drawPath(path, Color.White)
            }
        }
    }
}
