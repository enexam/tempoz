package com.example.tempoz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.tempoz.BeatGrid
import com.example.tempoz.formatBpm
import com.example.tempoz.ui.theme.TempozTheme
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val SampleRate = 48000

/**
 * The "album-art" centerpiece: a warm metronome face whose pulse is locked to
 * the audible click.
 *
 * Each frame it reads [audiblePositionMs] (the latency-compensated playhead),
 * finds the current beat via [BeatGrid], and derives the bloom envelope purely
 * from time-since-onset — so the visual beat matches what you hear instead of
 * free-running and drifting. [bpm], [beatsPerBar], [firstBeatOffsetFrames], and
 * [speed] are read live, so edits apply on the next frame without restarting
 * the bar.
 *
 * The BeatGrid is computed with effectiveBpm = bpm * speed and
 * effectiveOffset = firstBeatOffsetFrames / speed so the bloom stays aligned
 * with the audible (time-stretched) click at s != 1.
 *
 * Tapping the face calls [onTap] (tap-tempo).
 */
@Composable
fun PulseCore(
    bpm: Double,
    beatsPerBar: Int,
    isPlaying: Boolean,
    firstBeatOffsetFrames: Long,
    speed: Float,
    audiblePositionMs: () -> Long,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    ringSize: Dp = 252.dp,
) {
    val scheme = MaterialTheme.colorScheme
    var pulse by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableIntStateOf(0) }

    val currentBpm by rememberUpdatedState(bpm)
    val currentBeats by rememberUpdatedState(beatsPerBar)
    val currentOffset by rememberUpdatedState(firstBeatOffsetFrames)
    val currentSpeed by rememberUpdatedState(speed)
    val positionProvider by rememberUpdatedState(audiblePositionMs)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            pulse = 0f
            beat = 0
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos { }
            val beatsNow = currentBeats.coerceAtLeast(1)
            val s = currentSpeed.toDouble().coerceAtLeast(0.01)
            // effectiveBpm = bpm * speed: the click plays at this tempo in the
            // output timeline (what the listener hears after time-stretch).
            val effectiveBpm = (currentBpm * s).coerceAtLeast(1.0)
            // effectiveOffset = firstBeatOffsetFrames / speed: source frame offset
            // converted to output frames so the BeatGrid aligns with the audio.
            val effectiveOffset = Math.round(currentOffset / s)
            // Audible position → 48 kHz output frame; BeatGrid uses output units.
            val frame = positionProvider() * (SampleRate / 1000)
            val k = BeatGrid.beatIndexAt(frame, effectiveOffset, effectiveBpm, SampleRate)
            if (k < 0) {
                pulse = 0f
                beat = 0
            } else {
                val onset = BeatGrid.beatOnsetFrame(k, effectiveOffset, effectiveBpm, SampleRate)
                val sinceMs = (frame - onset).toDouble() / (SampleRate / 1000.0)
                val decayMs = (60_000.0 / effectiveBpm * 0.5).coerceIn(90.0, 240.0)
                val env = 1.0 - (sinceMs / decayMs).coerceIn(0.0, 1.0)
                pulse = (env * env).toFloat()
                beat = (k % beatsNow).toInt() + 1
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onTap() }
                .graphicsLayer {
                    val s = 1f + 0.035f * pulse
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = pulse
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f
                val discR = maxR * 0.74f

                // Warm bloom that swells on the beat.
                drawCircle(
                    color = scheme.primary.copy(alpha = 0.18f * p),
                    radius = discR * (1f + 0.12f + 0.14f * p),
                    center = center,
                )
                drawCircle(
                    color = scheme.primary.copy(alpha = 0.10f * p),
                    radius = discR * (1f + 0.26f + 0.22f * p),
                    center = center,
                )

                // The metronome face.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(scheme.surfaceContainerLowest, scheme.surfaceVariant),
                        center = center,
                        radius = discR,
                    ),
                    radius = discR,
                    center = center,
                )
                // Seated rim: a soft outer line and a rust inner rim.
                drawCircle(
                    color = scheme.outlineVariant,
                    radius = discR,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = scheme.primary.copy(alpha = 0.45f + 0.35f * p),
                    radius = discR * 0.9f,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Beat ticks around the rim; the active beat lights up in rust.
                val n = currentBeats.coerceAtLeast(1)
                for (i in 0 until n) {
                    val angle = (-PI / 2.0 + i * 2.0 * PI / n).toFloat()
                    val dir = Offset(cos(angle), sin(angle))
                    val isDown = i == 0
                    val active = isPlaying && (i + 1) == beat
                    val innerFactor = if (isDown) 0.80f else 0.85f
                    val outer = discR * 0.97f
                    val inner = discR * innerFactor
                    drawLine(
                        color = when {
                            active -> scheme.primary
                            isDown -> scheme.primary.copy(alpha = 0.55f)
                            else -> scheme.outlineVariant
                        },
                        start = center + dir * inner,
                        end = center + dir * outer,
                        strokeWidth = (if (isDown || active) 3.dp else 2.dp).toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatBpm(bpm),
                    style = MaterialTheme.typography.displayLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying && beat > 0) {
                Text(
                    text = "BEAT",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = beat.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "/  $beatsPerBar",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "$beatsPerBar / 4",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "Pulse Core — light", showBackground = true, backgroundColor = 0xFFEFE6D2)
@Composable
private fun PulseCorePreviewLight() {
    TempozTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            PulseCore(
                bpm = 126.0,
                beatsPerBar = 4,
                isPlaying = false,
                firstBeatOffsetFrames = 0L,
                speed = 1.0f,
                audiblePositionMs = { 0L },
                onTap = {},
                modifier = Modifier.width(320.dp),
            )
        }
    }
}

@Preview(name = "Pulse Core — dark", showBackground = true, backgroundColor = 0xFF18120B)
@Composable
private fun PulseCorePreviewDark() {
    TempozTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            PulseCore(
                bpm = 126.0,
                beatsPerBar = 4,
                isPlaying = false,
                firstBeatOffsetFrames = 0L,
                speed = 1.0f,
                audiblePositionMs = { 0L },
                onTap = {},
                modifier = Modifier.width(320.dp),
            )
        }
    }
}
