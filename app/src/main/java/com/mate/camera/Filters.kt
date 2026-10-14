package com.mate.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraMetadata
import android.media.ExifInterface
import android.net.Uri
import androidx.camera.video.Quality
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GridType(val label: String) { Off("Off"), Thirds("3×3"), Quarters("4×4"), Golden("Golden"), Cross("Cross") }
enum class FlashOpt(val label: String) { Off("Off"), Auto("Auto"), On("On") }
enum class VideoQ(val label: String, val q: Quality) {
    HD("720p", Quality.HD), FHD("1080p", Quality.FHD), UHD("4K", Quality.UHD)
}
enum class Wb(val label: String, val mode: Int) {
    Auto("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    Daylight("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    Cloudy("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    Tungsten("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    Fluor("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

val ShutterSpeeds = listOf(1000, 500, 250, 125, 60, 30, 15, 8)

/** All user-tweakable camera state in one place. */
class CamSettings {
    var tab by mutableIntStateOf(0)
    var filter by mutableIntStateOf(0)
    var grid by mutableStateOf(GridType.Thirds)
    var flash by mutableStateOf(FlashOpt.Off)
    var timer by mutableIntStateOf(0)
    var quality by mutableStateOf(VideoQ.FHD)
    var wb by mutableStateOf(Wb.Auto)

    var manual by mutableStateOf(false)
    var iso by mutableIntStateOf(200)
    var shutter by mutableIntStateOf(60) // 1/x s
    var ev by mutableIntStateOf(0)

    // device capabilities, filled in after the camera binds
    var manualOk by mutableStateOf(false)
    var isoMin by mutableIntStateOf(100)
    var isoMax by mutableIntStateOf(100)
    var expMin by mutableLongStateOf(1_000_000L)
    var expMax by mutableLongStateOf(1_000_000L)
    var evMin by mutableIntStateOf(0)
    var evMax by mutableIntStateOf(0)
    var evStep by mutableFloatStateOf(0f)
}

class Filter(val name: String, val matrix: ColorMatrix?)

private fun cm(vararg v: Float) = ColorMatrix(floatArrayOf(*v))

internal fun contrast(c: Float, lift: Float = 0f): ColorMatrix {
    val t = (-0.5f * c + 0.5f) * 255f + lift
    return cm(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun sat(s: Float, then: ColorMatrix? = null) =
    ColorMatrix().apply { setSaturation(s); if (then != null) postConcat(then) }

val Filters = listOf(
    Filter("Original", null),
    Filter("Vivid", sat(1.5f, contrast(1.1f))),
    Filter("Mono", sat(0f)),
    Filter("Noir", sat(0f, contrast(1.4f))),
    Filter(
        "Warm", cm(
            1.10f, 0f, 0f, 0f, 8f,
            0f, 1.00f, 0f, 0f, 0f,
            0f, 0f, 0.86f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    ),
    Filter(
        "Cool", cm(
            0.88f, 0f, 0f, 0f, 0f,
            0f, 1.00f, 0f, 0f, 0f,
            0f, 0f, 1.14f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f,
        )
    ),
    Filter("Fade", sat(0.85f, contrast(0.82f, 22f))),
    Filter(
        "Sepia", cm(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    ),
    Filter("Punch", sat(1.25f, contrast(1.25f))),
)

/** Bakes a colour filter into a saved JPEG (also applies EXIF rotation so the file stays upright). */
suspend fun applyFilter(ctx: Context, uri: Uri, matrix: ColorMatrix) = withContext(Dispatchers.IO) {
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
        val out = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(upright, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        cr.openOutputStream(uri, "wt")?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }
    Unit
}
