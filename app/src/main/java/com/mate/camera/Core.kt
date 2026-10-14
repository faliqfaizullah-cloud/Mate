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

fun loadPhotos(ctx: Context): List<Uri> {
    val items = mutableListOf<Pair<Uri, Long>>()
    fun scan(base: Uri, id: String, date: String, path: String, folder: String) {
        ctx.contentResolver.query(
            base, arrayOf(id, date), "$path LIKE ?", arrayOf("$folder%"), "$date DESC"
        )?.use { c ->
            val ic = c.getColumnIndexOrThrow(id)
            val dc = c.getColumnIndexOrThrow(date)
            while (c.moveToNext()) items += ContentUris.withAppendedId(base, c.getLong(ic)) to c.getLong(dc)
        }
    }
    scan(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mate"
    )
    scan(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mate"
    )
    return items.sortedByDescending { it.second }.map { it.first }
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
