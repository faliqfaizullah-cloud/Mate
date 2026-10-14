package com.mate.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { MateApp() } }
    }
}

@Composable
fun MateApp() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var showCollection by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf(loadPhotos(ctx)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraScreen(
                lastPhoto = photos.firstOrNull(),
                onSaved = { photos = loadPhotos(ctx) },
                onOpenCollection = { photos = loadPhotos(ctx); showCollection = true },
            )
        } else {
            PermissionScreen { launcher.launch(Manifest.permission.CAMERA) }
        }
        AnimatedVisibility(
            visible = showCollection,
            enter = slideInVertically(tween(420)) { it },
            exit = slideOutVertically(tween(320)) { it },
        ) { CollectionScreen(photos) { showCollection = false } }
    }
    BackHandler(showCollection) { showCollection = false }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    val haptics = Haptics(LocalContext.current, LocalView.current)
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mate", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Allow camera access to start shooting.", color = Color(0xFF8E8E93), fontSize = 16.sp)
        Box(
            Modifier.padding(top = 32.dp).clip(RoundedCornerShape(50)).background(Mint)
                .tap { haptics.click(); onAllow() }
                .padding(horizontal = 36.dp, vertical = 16.dp)
        ) { Text("Allow", color = Color.Black, fontWeight = FontWeight.SemiBold) }
    }
}

fun Modifier.tap(onClick: () -> Unit): Modifier = clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick
)

private data class FocusTap(val pos: Offset, val id: Long)

private val Presets = listOf(1f, 2f, 3.2f) // 24mm, 48mm, ~77mm equivalents

@Composable
fun CameraScreen(lastPhoto: Uri?, onSaved: () -> Unit, onOpenCollection: () -> Unit) {
    val ctx = LocalContext.current
    val hapticsView = LocalView.current
    val h = remember(hapticsView) { Haptics(ctx, hapticsView) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var useFront by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(ShutterStyle.MINT) }
    var grid by remember { mutableStateOf(true) }
    val zoom = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }
    var focus by remember { mutableStateOf<FocusTap?>(null) }

    fun maxZoom() = (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f)
    fun presets() = Presets.filter { it <= maxZoom() + 0.01f }.ifEmpty { listOf(1f) }

    LaunchedEffect(useFront) {
        val provider = ctx.cameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        camera = runCatching { provider.bindToLifecycle(owner, selector, preview, imageCapture) }.getOrNull()
        zoom.snapTo(1f)
    }
    LaunchedEffect(camera) {
        snapshotFlow { zoom.value }.collect { z ->
            val st = camera?.cameraInfo?.zoomState?.value
            camera?.cameraControl?.setZoomRatio(z.coerceIn(st?.minZoomRatio ?: 1f, st?.maxZoomRatio ?: 1f))
        }
    }
    DisposableEffect(Unit) {
        val l = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        l.enable()
        onDispose { l.disable() }
    }

    fun capture() {
        val name = "MATE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mate")
        }
        val opts = ImageCapture.OutputFileOptions
            .Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        scope.launch { flash.snapTo(0.85f); flash.animateTo(0f, tween(240)) }
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { h.confirm(); onSaved() }
            override fun onError(e: ImageCaptureException) { h.double() }
        })
    }

    fun cyclePill() {
        val ps = presets()
        val next = ps.firstOrNull { it > zoom.value + 0.05f } ?: ps.first()
        h.tick()
        scope.launch { zoom.animateTo(next, tween(380)); h.tick() }
    }

    val panelColor by animateColorAsState(style.panel, tween(300), label = "panel")

    @Composable
    fun Viewfinder(modifier: Modifier) {
        val ringAlpha = remember { Animatable(0f) }
        val ringScale = remember { Animatable(1.4f) }
        LaunchedEffect(focus) {
            if (focus != null) {
                ringAlpha.snapTo(1f); ringScale.snapTo(1.4f)
                launch { ringScale.animateTo(1f, tween(240)) }
                delay(900)
                ringAlpha.animateTo(0f, tween(300))
            }
        }
        Box(
            modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            focus = FocusTap(o, System.nanoTime())
                            h.tick()
                            val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                        },
                        onDoubleTap = { h.double(); useFront = !useFront },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ ->
                        val before = presets().firstOrNull { abs(it - zoom.value) < 0.04f }
                        val nz = (zoom.value * z).coerceIn(1f, maxZoom())
                        scope.launch { zoom.snapTo(nz) }
                        val after = presets().firstOrNull { abs(it - nz) < 0.04f }
                        if (after != null && after != before) h.tick() // detent
                    }
                }
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                }
            }
            focus?.let { f ->
                Box(
                    Modifier
                        .offset { IntOffset((f.pos.x - 32.dp.toPx()).roundToInt(), (f.pos.y - 32.dp.toPx()).roundToInt()) }
                        .size(64.dp)
                        .graphicsLayer { alpha = ringAlpha.value; scaleX = ringScale.value; scaleY = ringScale.value }
                        .border(1.5.dp, Sun, CircleShape)
                )
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
            val mm = (24 * zoom.value).roundToInt()
            val active = presets().any { abs(it - zoom.value) < 0.02f }
            FocalPill(
                mm, active, ::cyclePill,
                Modifier
                    .align(if (land) BiasAlignment(-0.8f, 0f) else BiasAlignment(-0.1f, 1f))
                    .padding(bottom = if (land) 0.dp else 22.dp)
            )
        }
    }

    @Composable
    fun Controls(modifier: Modifier) {
        val thumbScale = remember { Animatable(1f) }
        LaunchedEffect(lastPhoto) {
            thumbScale.snapTo(0.7f)
            thumbScale.animateTo(1f, spring(0.45f, Spring.StiffnessMedium))
        }
        Box(modifier) {
            Thumb(
                lastPhoto, style,
                Modifier
                    .align(if (land) Alignment.TopCenter else Alignment.CenterStart)
                    .padding(if (land) 28.dp else 0.dp).padding(start = if (land) 0.dp else 28.dp)
                    .scale(thumbScale.value)
            ) { h.click(); onOpenCollection() }
            Shutter(
                style, land, { h.press() }, { capture() },
                Modifier.align(if (land) Alignment.Center else BiasAlignment(-0.1f, 0f))
            )
            HalftoneButton(
                style.dots,
                onClick = { h.double(); style = style.next() },
                onLong = { h.click(); grid = !grid },
                modifier = Modifier
                    .align(if (land) Alignment.BottomCenter else BiasAlignment(0.62f, 0f))
                    .padding(if (land) 28.dp else 0.dp)
            )
        }
    }

    if (!land) {
        Column(Modifier.fillMaxSize().background(panelColor)) {
            Viewfinder(Modifier.weight(1f).fillMaxWidth())
            Controls(Modifier.fillMaxWidth().background(panelColor).navigationBarsPadding().height(168.dp))
        }
    } else {
        Row(Modifier.fillMaxSize().background(panelColor)) {
            Viewfinder(Modifier.weight(1f).fillMaxHeight())
            Controls(Modifier.fillMaxHeight().background(panelColor).navigationBarsPadding().width(140.dp))
        }
    }
}

@Composable
fun FocalPill(mm: Int, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape).tap(onClick)
    ) {
        Text(
            "${mm}mm",
            color = if (active) Sun else Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

@Composable
fun Shutter(style: ShutterStyle, vertical: Boolean, onPress: () -> Unit, onTap: () -> Unit, modifier: Modifier) {
    val press by rememberUpdatedState(onPress)
    val tapped by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "press"
    )
    val w by animateDpAsState(if (style.round) 76.dp else if (vertical) 68.dp else 104.dp, spring(0.7f), label = "w")
    val hh by animateDpAsState(if (style.round) 76.dp else if (vertical) 104.dp else 68.dp, spring(0.7f), label = "h")
    val fill by animateColorAsState(style.fill, tween(300), label = "fill")
    val ring by animateColorAsState(style.ring, tween(300), label = "ring")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .size(w, hh)
            .scale(scale)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; press(); tryAwaitRelease(); pressed = false },
                    onTap = { tapped() },
                )
            }
            .border(3.dp, ring, shape)
            .padding(7.dp)
            .clip(shape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (style.plus) Canvas(Modifier.size(24.dp)) {
            val c = Color(0xFF1A1A1A)
            val s = 2.dp.toPx()
            drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), s, StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.2f, size.height / 2), Offset(size.width * 0.8f, size.height / 2), s, StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HalftoneButton(color: Color, onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(48.dp).combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onClick, onLongClick = onLong,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val n = 7
            val step = size.width / n
            for (x in 0 until n) for (y in 0 until n) {
                drawCircle(color, 0.7.dp.toPx() + x * 0.16.dp.toPx(), Offset(step * (x + 0.5f), step * (y + 0.5f)))
            }
        }
    }
}

@Composable
fun Thumb(uri: Uri?, style: ShutterStyle, modifier: Modifier, onClick: () -> Unit) {
    val bmp by rememberThumb(uri, 200, 200)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.size(56.dp).clip(shape)
            .background(if (style.panel == Color.White) Color(0xFFEDEDED) else Color(0xFF262626))
            .tap(onClick)
    ) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
