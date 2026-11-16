package com.mate.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub "owner/repo" whose Releases hold the APKs. Change this if you rename the repo. */
const val UPDATE_REPO = "faliqfaizullah-cloud/Mate"

class UpdateInfo(val version: String, val notes: String, val url: String, val size: Long)

enum class Phase { Idle, Checking, UpToDate, Available, Downloading, Error }

fun currentVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

private fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
    .split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

fun isNewer(remote: String, local: String): Boolean {
    val a = parts(remote)
    val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

class UpdateState {
    var phase by mutableStateOf(Phase.Idle)
    var info by mutableStateOf<UpdateInfo?>(null)
    var progress by mutableFloatStateOf(0f)
    var dialog by mutableStateOf(false)
    var message by mutableStateOf("")
    private var file: File? = null

    val downloaded get() = file != null

    /** Looks at the latest GitHub release. [silent] = don't pop a dialog or report errors (launch-time check). */
    suspend fun check(ctx: Context, silent: Boolean) {
        if (phase == Phase.Checking || phase == Phase.Downloading) return
        phase = Phase.Checking
        message = ""
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        val latest = result.getOrNull()
        when {
            result.isFailure -> {
                phase = if (silent) Phase.Idle else Phase.Error
                message = "Couldn't reach GitHub. Check your connection."
            }
            latest != null && isNewer(latest.version, currentVersion(ctx)) -> {
                info = latest; file = null
                phase = Phase.Available
                if (!silent) dialog = true
            }
            else -> phase = Phase.UpToDate
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val c = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (c.responseCode == 404) return null          // no releases yet
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return UpdateInfo(
                    json.getString("tag_name"), json.optString("body"),
                    a.getString("browser_download_url"), a.optLong("size"),
                )
            }
        }
        return null
    }

    /** Downloads (if needed) and hands the APK to Android's installer. The app is updated in place. */
    suspend fun start(ctx: Context) {
        val i = info ?: return
        if (file == null) {
            phase = Phase.Downloading
            progress = 0f
            message = ""
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "Mate-update.apk")
                    val c = URL(i.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: i.size
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var n = input.read(buf)
                            while (n >= 0) {
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                                n = input.read(buf)
                            }
                        }
                    }
                    out
                }
            }
            val ok = f.getOrNull()
            if (ok == null) { phase = Phase.Error; message = "Download failed. Try again."; return }
            file = ok
        }
        phase = Phase.Available
        install(ctx)
    }

    private fun install(ctx: Context) {
        val f = file ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            message = "Allow Mate to install updates in Settings, come back and tap Install."
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun updateStatusText(ctx: Context, u: UpdateState): String = when (u.phase) {
    Phase.Checking -> "Checking for updates…"
    Phase.UpToDate -> "Mate v${currentVersion(ctx)} is up to date"
    Phase.Available -> "Update ${u.info?.version ?: ""} is available"
    Phase.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
    Phase.Error -> u.message
    Phase.Idle -> "Mate v${currentVersion(ctx)}"
}

@Composable
fun UpdateDialog(u: UpdateState, h: Haptics) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = u.info ?: return
    val busy = u.phase == Phase.Downloading

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).tap { if (!busy) u.dialog = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1C1C1E)).padding(22.dp)
                .tap { },
        ) {
            Text("Update available", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Mate ${info.version}" + if (info.size > 0) "  ·  ${"%.1f".format(info.size / 1_048_576f)} MB" else "",
                Modifier.padding(top = 4.dp), color = Sun, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            if (info.notes.isNotBlank()) Text(
                info.notes.trim().take(400), Modifier.padding(top = 12.dp),
                color = Color(0xFFB0B0B5), fontSize = 13.sp, maxLines = 7,
            )
            Text(
                "Installs over your current app. Your photos and settings are kept.",
                Modifier.padding(top = 12.dp), color = Color(0xFF8E8E93), fontSize = 12.sp,
            )
            if (busy) Canvas(Modifier.padding(top = 16.dp).fillMaxWidth().height(6.dp)) {
                drawRoundRect(Color(0xFF3A3A3C), Offset.Zero, size, CornerRadius(size.height / 2))
                drawRoundRect(Mint, Offset.Zero, Size(size.width * u.progress, size.height), CornerRadius(size.height / 2))
            }
            if (u.message.isNotBlank()) Text(
                u.message, Modifier.padding(top = 12.dp),
                color = if (u.phase == Phase.Error) Color(0xFFFF6B6B) else Color(0xFFFFD60A), fontSize = 12.sp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0xFF2C2C2E))
                        .tap { if (!busy) { h.click(); u.dialog = false } }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Later", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (busy) Color(0xFF2C2C2E) else Mint)
                        .tap { if (!busy) { h.confirm(); scope.launch { u.start(ctx) } } }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            busy -> "${(u.progress * 100).toInt()}%"
                            u.downloaded -> "Install"
                            else -> "Update now"
                        },
                        color = if (busy) Color.White else Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Small glowing pill shown in the viewfinder when a new version is out. */
@Composable
fun UpdatePill(version: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(Color.Black.copy(alpha = 0.55f)).tap(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("↑ $version", color = Sun, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
