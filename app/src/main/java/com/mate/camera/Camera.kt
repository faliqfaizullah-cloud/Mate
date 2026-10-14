package com.mate.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private data class FocusTap(val pos: Offset, val id: Long)

private val Presets = listOf(1f, 2f, 3.2f) // 24mm, 48mm, ~77mm equivalents
private val RecRed = Color(0xFFFF3B30)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(lastPhoto: Uri?, onSaved: () -> Unit, onOpenCollection: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val h = remember(view) { Haptics(ctx, view) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val s = remember { CamSettings() }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember(s.jpegQ, s.maxQuality) {
        ImageCapture.Builder()
            .setCaptureMode(
                if (s.maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setJpegQuality(s.jpegQ)
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val recorder = remember(s.quality) {
        Recorder.Builder().setQualitySelector(
            QualitySelector.from(s.quality.q, FallbackStrategy.lowerQualityOrHigherThan(s.quality.q))
        ).build()
    }
    val videoCapture = remember(recorder) { VideoCapture.withOutput(recorder) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var useFront by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(ShutterStyle.MINT) }
    var videoMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recSeconds by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<FocusTap?>(null) }
    var tilt by remember { mutableFloatStateOf(0f) }
    var flatPhone by remember { mutableStateOf(false) }
    val zoom = remember { Animatable(1f) }
    val flash = remember { Animatable(0f) }

    var audioOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioOk = it }

    fun maxZoom() = (camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f)
    fun presets() = Presets.filter { it <= maxZoom() + 0.01f }.ifEmpty { listOf(1f) }
    fun stamp() = "MATE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ---- bind use cases (photo: preview+image, video: preview+video) --------------------
    LaunchedEffect(useFront, videoMode, videoCapture, imageCapture) {
        val provider = ctx.cameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        val basic = if (videoMode) arrayOf<UseCase>(preview, videoCapture) else arrayOf<UseCase>(preview, imageCapture)
        val withAnalysis = if (videoMode) basic else arrayOf<UseCase>(preview, imageCapture, analysis)
        camera = runCatching { provider.bindToLifecycle(owner, selector, *withAnalysis) }.getOrNull()
            ?: runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, selector, *basic) }.getOrNull()
        zoom.snapTo(1f)
        camera?.let { c ->
            runCatching {
                val ex = c.cameraInfo.exposureState
                if (ex.isExposureCompensationSupported) {
                    s.evMin = ex.exposureCompensationRange.lower
                    s.evMax = ex.exposureCompensationRange.upper
                    s.evStep = ex.exposureCompensationStep.toFloat()
                } else { s.evMin = 0; s.evMax = 0 }
                s.ev = s.ev.coerceIn(s.evMin, s.evMax)
            }
            runCatching {
                val info = Camera2CameraInfo.from(c.cameraInfo)
                val ir = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val er = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val minF = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                s.minFocus = minF ?: 0f
                s.mfOk = minF != null && minF > 0f
                if (!s.mfOk) s.mf = false
                s.manualOk = ir != null && er != null
                if (ir != null) { s.isoMin = ir.lower; s.isoMax = ir.upper; s.iso = s.iso.coerceIn(ir.lower, ir.upper) }
                if (er != null) { s.expMin = er.lower; s.expMax = er.upper }
                if (!s.manualOk) s.manual = false
            }
        }
    }
    LaunchedEffect(camera) {
        snapshotFlow { zoom.value }.collect { z ->
            val st = camera?.cameraInfo?.zoomState?.value
            camera?.cameraControl?.setZoomRatio(z.coerceIn(st?.minZoomRatio ?: 1f, st?.maxZoomRatio ?: 1f))
        }
    }

    // ---- live filter on the preview ---------------------------------------------------
    LaunchedEffect(s.filter) {
        val m = Filters[s.filter].matrix
        if (m == null) previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        else previewView.setLayerType(
            View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
        )
    }

    // ---- manual ISO / shutter / white balance via Camera2 interop ---------------------------
    LaunchedEffect(camera, s.manual, s.iso, s.shutter, s.wb, s.mf, s.focusPos, s.aeLock) {
        val c = camera ?: return@LaunchedEffect
        runCatching {
            val b = CaptureRequestOptions.Builder()
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, s.wb.mode)
            if (s.manual && s.manualOk) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(s.isoMin, s.isoMax))
                val ns = (1_000_000_000L / s.shutter).coerceIn(s.expMin, s.expMax)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            }
            if (s.mf && s.mfOk) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - s.focusPos) * s.minFocus)
            }
            if (s.aeLock && !s.manual) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build())
        }
    }
    LaunchedEffect(camera, s.ev) { runCatching { camera?.cameraControl?.setExposureCompensationIndex(s.ev) } }
    LaunchedEffect(camera, s.flash, videoMode, imageCapture) {
        imageCapture.flashMode = when (s.flash) {
            FlashOpt.Off -> ImageCapture.FLASH_MODE_OFF
            FlashOpt.Auto -> ImageCapture.FLASH_MODE_AUTO
            FlashOpt.On -> ImageCapture.FLASH_MODE_ON
        }
        runCatching { camera?.cameraControl?.enableTorch(videoMode && s.flash == FlashOpt.On) }
    }
    LaunchedEffect(recording) {
        if (recording != null) {
            recSeconds = 0
            while (true) { delay(1000); recSeconds++ }
        }
    }
    // histogram analyser
    DisposableEffect(analysis) {
        var last = 0L
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (s.histogram && now - last > 200) {
                last = now
                runCatching {
                    val plane = proxy.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    val bins = IntArray(32)
                    var y = 0
                    while (y < proxy.height) {
                        var x = 0
                        while (x < proxy.width) {
                            bins[(buf.get(y * rs + x).toInt() and 0xFF) shr 3]++
                            x += 6
                        }
                        y += 6
                    }
                    val mx = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
                    val arr = FloatArray(32) { bins[it] / mx.toFloat() }
                    previewView.post { s.hist = arr }
                }
            }
            proxy.close()
        }
        onDispose { analysis.clearAnalyzer() }
    }
    // spirit level
    DisposableEffect(s.level) {
        if (!s.level) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return@DisposableEffect onDispose { }
        var wasLevel = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                flatPhone = abs(e.values[2]) > 8.3f
                val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                val d = a - 90f * Math.round(a / 90f)
                tilt = tilt * 0.8f + d * 0.2f
                val lv = abs(tilt) < 1f && !flatPhone
                if (lv && !wasLevel) h.tick()
                wasLevel = lv
            }
            override fun onAccuracyChanged(sn: Sensor?, acc: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    DisposableEffect(imageCapture) {
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
    BackHandler(sheetOpen) { sheetOpen = false }

    // ---- actions ----------------------------------------------------------------------
    fun capture() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, stamp())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mate")
        }
        val opts = ImageCapture.OutputFileOptions
            .Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = useFront && s.mirror })
            .build()
        val fm = Filters[s.filter].matrix
        scope.launch { flash.snapTo(0.85f); flash.animateTo(0f, tween(240)) }
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                h.confirm()
                val u = r.savedUri
                if (fm != null && u != null) scope.launch { applyFilter(ctx, u, fm); onSaved() } else onSaved()
            }
            override fun onError(e: ImageCaptureException) { h.double() }
        })
    }

    fun startRecording() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, stamp())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mate")
        }
        val out = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = videoCapture.output.prepareRecording(ctx, out)
        if (audioOk) pending = pending.withAudioEnabled()
        h.confirm()
        recording = pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                if (ev.hasError()) h.double() else h.confirm()
                onSaved()
            }
        }
    }

    fun onShutter() {
        if (countdownJob?.isActive == true) { countdownJob?.cancel(); countdown = 0; h.click(); return }
        if (videoMode && recording != null) { recording?.stop(); return }
        val go = { if (videoMode) startRecording() else capture() }
        if (s.timer > 0) {
            countdownJob = scope.launch {
                for (i in s.timer downTo 1) { countdown = i; h.tick(); delay(1000) }
                countdown = 0
                go()
            }
        } else go()
    }

    fun setMode(video: Boolean) {
        if (recording != null) return
        h.double()
        videoMode = video
        if (video && !audioOk) audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun cyclePill() {
        val ps = presets()
        val next = ps.firstOrNull { it > zoom.value + 0.05f } ?: ps.first()
        h.tick()
        scope.launch { zoom.animateTo(next, tween(380)); h.tick() }
    }

    val panelColor by animateColorAsState(style.panel, tween(300), label = "panel")

    // ---- UI -------------------------------------------------------------------------
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
                            sheetOpen = false
                            focus = FocusTap(o, System.nanoTime())
                            h.tick()
                            val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                            val action = if (s.mf) FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AE).build()
                            else FocusMeteringAction.Builder(pt).build()
                            camera?.cameraControl?.startFocusAndMetering(action)
                        },
                        onDoubleTap = { if (recording == null) { h.double(); useFront = !useFront } },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ ->
                        val before = presets().firstOrNull { abs(it - zoom.value) < 0.04f }
                        val nz = (zoom.value * z).coerceIn(1f, maxZoom())
                        scope.launch { zoom.snapTo(nz) }
                        val after = presets().firstOrNull { abs(it - nz) < 0.04f }
                        if (after != null && after != before) h.tick()
                    }
                }
        ) {
            AndroidView({ previewView }, Modifier.fillMaxSize())

            if (s.grid != GridType.Off) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.38f)
                val sw = 1.dp.toPx()
                fun vl(f: Float) = drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), sw)
                fun hl(f: Float) = drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), sw)
                when (s.grid) {
                    GridType.Thirds -> listOf(1f / 3, 2f / 3).forEach { vl(it); hl(it) }
                    GridType.Quarters -> listOf(0.25f, 0.5f, 0.75f).forEach { vl(it); hl(it) }
                    GridType.Golden -> listOf(0.382f, 0.618f).forEach { vl(it); hl(it) }
                    GridType.Cross -> {
                        val cx = size.width / 2; val cy = size.height / 2; val L = 14.dp.toPx()
                        drawLine(Color.White.copy(alpha = 0.7f), Offset(cx - L, cy), Offset(cx + L, cy), sw * 1.5f, StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, cy - L), Offset(cx, cy + L), sw * 1.5f, StrokeCap.Round)
                    }
                    GridType.Off -> {}
                }
            }

            if (s.level && !flatPhone) Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2; val cy = size.height / 2; val half = 54.dp.toPx()
                val col = if (abs(tilt) < 1.2f) Sun else Color.White.copy(alpha = 0.8f)
                val sw = 2.dp.toPx()
                rotate(tilt, Offset(cx, cy)) { drawLine(col, Offset(cx - half, cy), Offset(cx + half, cy), sw, StrokeCap.Round) }
                val edge = col.copy(alpha = 0.5f)
                drawLine(edge, Offset(cx - half - 36.dp.toPx(), cy), Offset(cx - half - 16.dp.toPx(), cy), sw, StrokeCap.Round)
                drawLine(edge, Offset(cx + half + 16.dp.toPx(), cy), Offset(cx + half + 36.dp.toPx(), cy), sw, StrokeCap.Round)
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

            // top: mode switch or recording timer
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)) {
                if (recording != null) RecPill(recSeconds, Modifier) else ModePill(videoMode, ::setMode, Modifier)
            }
            // HUD
            val hud = buildList {
                if (s.filter != 0) add(Filters[s.filter].name)
                if (s.manual) add("ISO ${s.iso} · 1/${s.shutter}")
            }.joinToString("  ·  ")
            if (hud.isNotEmpty()) Text(
                hud, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 18.dp, top = 18.dp),
                color = Sun, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            if (countdown > 0) Text(
                "$countdown", Modifier.align(Alignment.Center),
                color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Thin,
            )

            AfPill(
                s.mf, { if (s.mfOk) { h.double(); s.mf = !s.mf } else h.double() },
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 18.dp),
            )
            if (s.mf) FocusRuler(
                s.focusPos, focusLabel(s), { s.focusPos = it }, h,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp),
            )
            if (s.histogram) Histogram(
                s.hist, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 18.dp, top = 52.dp)
            )

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
                    .padding(if (land) 28.dp else 0.dp).padding(start = if (land) 0.dp else 20.dp)
                    .scale(thumbScale.value)
            ) { h.click(); onOpenCollection() }
            Shutter(
                style, land, videoMode, recording != null,
                { h.press() }, { onShutter() },
                Modifier.align(if (land) Alignment.Center else BiasAlignment(-0.1f, 0f))
            )
            HalftoneButton(
                style.dots,
                onClick = { h.click(); sheetOpen = !sheetOpen },
                onLong = { h.double(); style = style.next() },
                modifier = Modifier
                    .align(if (land) Alignment.BottomCenter else BiasAlignment(0.62f, 0f))
                    .padding(if (land) 28.dp else 0.dp)
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
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
        AnimatedVisibility(sheetOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).tap { sheetOpen = false })
        }
        AnimatedVisibility(
            sheetOpen,
            modifier = Modifier.align(if (land) Alignment.BottomStart else Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(240)) { it },
        ) {
            ToolsSheet(
                s, videoMode, recording != null, style,
                onStyle = { style = it; h.double() },
                onFlip = { if (recording == null) { h.double(); useFront = !useFront } },
                h = h,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            )
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
fun Shutter(
    style: ShutterStyle, vertical: Boolean, videoMode: Boolean, recording: Boolean,
    onPress: () -> Unit, onTap: () -> Unit, modifier: Modifier,
) {
    val press by rememberUpdatedState(onPress)
    val tapped by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "press"
    )
    val w by animateDpAsState(if (style.round) 76.dp else if (vertical) 68.dp else 104.dp, spring(0.7f), label = "w")
    val hh by animateDpAsState(if (style.round) 76.dp else if (vertical) 104.dp else 68.dp, spring(0.7f), label = "h")
    val inset by animateDpAsState(if (recording) 20.dp else 7.dp, spring(0.7f), label = "inset")
    val fill by animateColorAsState(if (videoMode) RecRed else style.fill, tween(300), label = "fill")
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
            .padding(inset)
            .clip(shape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (style.plus && !videoMode) Canvas(Modifier.size(24.dp)) {
            val c = Color(0xFF1A1A1A)
            val sw = 2.dp.toPx()
            drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), sw, StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.2f, size.height / 2), Offset(size.width * 0.8f, size.height / 2), sw, StrokeCap.Round)
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

/** Wide, rounded "mini screen" showing the most recent shot, with a dark bezel and soft gloss. */
@Composable
fun Thumb(uri: Uri?, style: ShutterStyle, modifier: Modifier, onClick: () -> Unit) {
    val bmp by rememberThumb(uri, 400, 300)
    Box(
        modifier.size(76.dp, 54.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0B0B)).tap(onClick).padding(2.5.dp)
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(13.5.dp)).background(Color(0xFF2A2A2A))) {
            Crossfade(bmp, Modifier.fillMaxSize(), label = "thumb") { b ->
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent))
                )
            )
        }
    }
}

/** Glowing AF / MF indicator. Tap to switch focus mode. */
@Composable
fun AfPill(mf: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = if (mf) Color.White else Sun
    val shape = RoundedCornerShape(50)
    Box(
        modifier.shadow(10.dp, shape, ambientColor = c, spotColor = c).clip(shape)
            .background(Color.Black.copy(alpha = 0.6f)).border(2.5.dp, c, shape)
            .tap(onClick).padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(if (mf) "MF" else "AF", color = c, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
    }
}

/** Manual focus ruler: drag left (near) and right (far). */
@Composable
fun FocusRuler(pos: Float, label: String, onChange: (Float) -> Unit, h: Haptics, modifier: Modifier) {
    val current by rememberUpdatedState(pos)
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(230.dp, 34.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.42f))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { c, dx ->
                        c.consume()
                        val np = (current + dx / size.width * 1.6f).coerceIn(0f, 1f)
                        if ((np * 20).roundToInt() != (current * 20).roundToInt()) h.tick()
                        change(np)
                    }
                }
        ) {
            val pad = 16.dp.toPx()
            val w = size.width - pad * 2
            for (i in 0..20) {
                val x = pad + w * i / 20f
                val major = i % 5 == 0
                val th = if (major) 14.dp.toPx() else 8.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.45f), Offset(x, size.height / 2 - th / 2), Offset(x, size.height / 2 + th / 2), 1.5.dp.toPx(), StrokeCap.Round)
            }
            val px = pad + w * pos
            drawLine(Color.White, Offset(px, size.height / 2 - 11.dp.toPx()), Offset(px, size.height / 2 + 11.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        }
        Text(label, Modifier.padding(top = 4.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Histogram(bins: FloatArray, modifier: Modifier) {
    Canvas(modifier.size(100.dp, 46.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.42f))) {
        val n = bins.size
        val bw = size.width / n
        bins.forEachIndexed { i, v ->
            val bh = v * size.height * 0.88f
            drawRect(Color.White.copy(alpha = 0.85f), Offset(i * bw + 0.5f, size.height - bh), Size((bw - 1f).coerceAtLeast(0.5f), bh))
        }
    }
}
