package com.ethic.vpn.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.vpn.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin

private const val SPEED_BASE = "https://speed.cloudflare.com"
private const val SPEED_SECONDS = 8_000L
private const val SPEED_STREAMS = 4
private val GAUGE_MARKS = listOf(0.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0)

private enum class SpeedPhase { Idle, Ping, Download, Upload, Done, Failed }

/** The speed test: a gauge whose needle follows the current speed, then ping, download and upload. */
@Composable
fun SpeedTestDialog(connected: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(SpeedPhase.Idle) }
    var current by remember { mutableStateOf(0.0) }
    var ping by remember { mutableStateOf<Int?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = phase in setOf(SpeedPhase.Ping, SpeedPhase.Download, SpeedPhase.Upload)

    fun run() {
        job = scope.launch {
            ping = null; down = null; up = null; current = 0.0
            phase = SpeedPhase.Ping
            ping = withContext(Dispatchers.IO) { measurePing() }
            if (ping == null) {
                phase = SpeedPhase.Failed
                return@launch
            }
            phase = SpeedPhase.Download
            down = measure(upload = false) { current = it }
            current = 0.0
            phase = SpeedPhase.Upload
            up = measure(upload = true) { current = it }
            current = 0.0
            phase = SpeedPhase.Done
        }
    }

    Dialog(onDismissRequest = { job?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Brand.Background).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.speed_title), color = Brand.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(stringResource(R.string.close)) }
            }
            SpeedGauge(current, running)
            Text(
                stringResource(
                    when (phase) {
                        SpeedPhase.Idle -> R.string.speed_idle
                        SpeedPhase.Ping -> R.string.speed_ping_phase
                        SpeedPhase.Download -> R.string.speed_down_phase
                        SpeedPhase.Upload -> R.string.speed_up_phase
                        SpeedPhase.Done -> R.string.speed_done
                        SpeedPhase.Failed -> R.string.speed_failed
                    },
                ),
                color = Brand.TextMuted, fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeedResult(stringResource(R.string.speed_ping), ping?.toString(), stringResource(R.string.speed_ms), Modifier.weight(1f))
                SpeedResult(stringResource(R.string.speed_down), down?.let(::formatMbps), stringResource(R.string.speed_mbps), Modifier.weight(1f))
                SpeedResult(stringResource(R.string.speed_up), up?.let(::formatMbps), stringResource(R.string.speed_mbps), Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = ::run, enabled = !running,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Cyan, contentColor = Color.Black),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(stringResource(if (phase == SpeedPhase.Idle) R.string.speed_start else R.string.speed_again), fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(if (connected) R.string.speed_via_vpn else R.string.speed_direct), color = Brand.TextMuted, fontSize = 12.sp)
        }
    }
}

private fun formatMbps(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

/** 0…1 along the arc; the scale is stretched at the low end, where most connections are. */
private fun gaugePosition(v: Double): Float {
    if (v <= 0) return 0f
    if (v >= GAUGE_MARKS.last()) return 1f
    val i = GAUGE_MARKS.indexOfLast { it <= v }
    val part = (v - GAUGE_MARKS[i]) / (GAUGE_MARKS[i + 1] - GAUGE_MARKS[i])
    return ((i + part) / (GAUGE_MARKS.size - 1)).toFloat()
}

@Composable
private fun SpeedGauge(value: Double, active: Boolean) {
    val p by animateFloatAsState(gaugePosition(value), spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "gauge")
    val track = Brand.Surface
    val accent = Brand.Cyan
    val muted = Brand.TextMuted
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(accent.copy(alpha = 0.6f), accent)), 135f, 270f * p, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                alpha = if (active || p > 0f) 1f else 0.6f,
            )
            val angle = Math.toRadians(135.0 + 270.0 * p)
            val c = Offset(size.width / 2, size.height / 2)
            val len = size.width / 2 - 50.dp.toPx()
            drawLine(Color.White, c, Offset(c.x + (cos(angle) * len).toFloat(), c.y + (sin(angle) * len).toFloat()), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, 7.dp.toPx(), c)
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb((muted.alpha * 255).toInt(), (muted.red * 255).toInt(), (muted.green * 255).toInt(), (muted.blue * 255).toInt())
                    textSize = 11.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                GAUGE_MARKS.forEachIndexed { i, mark ->
                    val a = Math.toRadians(135.0 + 270.0 * i / (GAUGE_MARKS.size - 1))
                    val r = size.width / 2 - 40.dp.toPx()
                    drawText(mark.toInt().toString(), c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat() + paint.textSize / 3, paint)
                }
            }
        }
        Column(Modifier.padding(top = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatMbps(value), color = Brand.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.speed_mbps), color = Brand.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SpeedResult(title: String, value: String?, unit: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Brand.Surface).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Brand.TextMuted, fontSize = 12.sp)
        Text(value ?: "—", color = Brand.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(unit, color = Brand.TextMuted, fontSize = 11.sp)
    }
}

/** Best of five small requests; blocking. */
private fun measurePing(): Int? {
    var best: Int? = null
    repeat(5) {
        val start = System.nanoTime()
        val ok = runCatching {
            (URL("$SPEED_BASE/__down?bytes=0").openConnection() as HttpURLConnection).run {
                connectTimeout = 5000; readTimeout = 5000
                inputStream.use { it.readBytes() }
                disconnect()
            }
        }.isSuccess
        if (ok) {
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            best = minOf(best ?: ms, ms)
        }
    }
    return best
}

/**
 * Runs [SPEED_STREAMS] connections for [SPEED_SECONDS], reporting the rate of each half second,
 * and returns the average in Mbit/s.
 */
private suspend fun measure(upload: Boolean, onRate: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
    val bytes = AtomicLong()
    val begin = System.nanoTime()
    val deadline = begin + SPEED_SECONDS * 1_000_000
    val workers = (1..SPEED_STREAMS).map {
        launch {
            val buffer = ByteArray(64 * 1024)
            while (isActive && System.nanoTime() < deadline) {
                runCatching {
                    val connection = (URL(if (upload) "$SPEED_BASE/__up" else "$SPEED_BASE/__down?bytes=100000000").openConnection() as HttpURLConnection)
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    try {
                        if (upload) {
                            connection.requestMethod = "POST"
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(25_000_000)
                            connection.outputStream.use { out ->
                                var sent = 0
                                while (sent < 25_000_000 && isActive && System.nanoTime() < deadline) {
                                    out.write(buffer)
                                    sent += buffer.size
                                    bytes.addAndGet(buffer.size.toLong())
                                }
                            }
                        } else {
                            connection.inputStream.use { input ->
                                while (isActive && System.nanoTime() < deadline) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
    }
    var last = 0L
    var lastTime = begin
    while (System.nanoTime() < deadline) {
        delay(500)
        val now = System.nanoTime()
        val total = bytes.get()
        val rate = (total - last) * 8.0 / ((now - lastTime) / 1e9) / 1_000_000
        withContext(Dispatchers.Main) { onRate(rate) }
        last = total
        lastTime = now
    }
    workers.forEach { it.cancel() }
    bytes.get() * 8.0 / ((System.nanoTime() - begin) / 1e9) / 1_000_000
}
