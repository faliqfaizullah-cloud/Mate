package com.mate.camera

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Every interaction in Mate has its own haptic "texture". */
class Haptics(context: Context, private val view: View) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    private fun play(effect: Int, fallback: Int) {
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            view.performHapticFeedback(fallback)
        }
    }

    /** Very light: zoom detents, focus, card taps. */
    fun tick() = play(VibrationEffect.EFFECT_TICK, HapticFeedbackConstants.CLOCK_TICK)

    /** Medium: buttons, thumbnail, sheet snap. */
    fun click() = play(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.VIRTUAL_KEY)

    /** Finger lands on the shutter. */
    fun press() = play(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.KEYBOARD_TAP)

    /** Photo saved: the satisfying thunk. */
    fun confirm() = play(VibrationEffect.EFFECT_HEAVY_CLICK, HapticFeedbackConstants.LONG_PRESS)

    /** Style switch, camera flip, errors. */
    fun double() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, HapticFeedbackConstants.LONG_PRESS)
}

val Mint = Color(0xFF00E58F)
val Sun = Color(0xFFFFD60A)
val Sky = Color(0xFF5AC8FA)
val Ink = Color(0xFF121212)

/** The three shutter looks from the design: mint pill / mono circle / sun pill with "+". */
enum class ShutterStyle(
    val panel: Color,
    val fill: Color,
    val ring: Color,
    val dots: Color,
    val plus: Boolean,
    val round: Boolean,
) {
    MINT(Color.White, Mint, Mint, Color(0xFF1A1A1A), false, false),
    MONO(Color.Black, Color.White, Color.White, Color.White, false, true),
    SUN(Color.Black, Sun, Sun, Color.White, true, false);

    fun next() = entries[(ordinal + 1) % entries.size]
}

suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

/** burst key-frame -> all frames of that burst (only bursts with 2+ frames). Refreshed by [loadPhotos]. */
object BurstStore {
    var groups: Map<Uri, List<Uri>> = emptyMap()
}

private class MediaItem(val uri: Uri, val date: Long, val name: String, val burst: String?)

private val BurstFolder = Regex("Pictures/Mate/(Burst_[^/]+)/?")

/** Photos + videos newest first. A burst appears once (as its first frame) and is recorded in [BurstStore]. */
fun loadPhotos(ctx: Context): List<Uri> {
    val items = mutableListOf<MediaItem>()
    fun scan(base: Uri, id: String, date: String, path: String, name: String, folder: String, image: Boolean) {
        ctx.contentResolver.query(
            base, arrayOf(id, date, path, name), "$path LIKE ?", arrayOf("$folder%"), "$date DESC"
        )?.use { c ->
            val ic = c.getColumnIndexOrThrow(id)
            val dc = c.getColumnIndexOrThrow(date)
            val pc = c.getColumnIndexOrThrow(path)
            val nc = c.getColumnIndexOrThrow(name)
            while (c.moveToNext()) {
                val rel = c.getString(pc) ?: ""
                val burst = if (image) BurstFolder.find(rel)?.groupValues?.get(1) else null
                items += MediaItem(
                    ContentUris.withAppendedId(base, c.getLong(ic)), c.getLong(dc), c.getString(nc) ?: "", burst
                )
            }
        }
    }
    scan(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME, "Pictures/Mate", true
    )
    scan(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DISPLAY_NAME, "Movies/Mate", false
    )
    val rows = mutableListOf<Pair<Uri, Long>>()
    val groups = mutableMapOf<Uri, List<Uri>>()
    items.filter { it.burst == null }.forEach { rows += it.uri to it.date }
    items.filter { it.burst != null }.groupBy { it.burst }.values.forEach { g ->
        val sorted = g.sortedBy { it.name }
        val rep = sorted.first().uri
        if (sorted.size > 1) groups[rep] = sorted.map { it.uri }
        rows += rep to g.maxOf { it.date }
    }
    BurstStore.groups = groups
    return rows.sortedByDescending { it.second }.map { it.first }
}

@Composable
fun rememberThumb(uri: Uri?, w: Int, h: Int): State<ImageBitmap?> {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(w, h), null).asImageBitmap() }
                .getOrNull()
        }
    }
}
