package com.example.tempoz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tempoz.formatBpm
import com.example.tempoz.quantizeBpm
import kotlin.math.roundToInt

// Output frames per millisecond at the engine's 48 kHz rate.
private const val FramesPerMs = 48L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSettingsSheet(
    trackName: String?,
    bpm: Double,
    beatsPerBar: Int,
    detectedBpm: Double?,
    beatOffsetFrames: Long,
    onBpmChange: (Double) -> Unit,
    onBeatsChange: (Int) -> Unit,
    onBeatOffsetChange: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            SheetTitle("Track settings", subtitle = trackName ?: "No track loaded")

            if (trackName == null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Load a track to adjust its tempo, time signature, and first beat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                return@Column
            }

            Spacer(Modifier.height(22.dp))

            // Tempo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TEMPO", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatBpm(bpm)} bpm",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onBpmChange(quantizeBpm(bpm - 1.0)) },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Remove, contentDescription = "Slower") }
                Slider(
                    value = bpm.toFloat().coerceIn(40f, 240f),
                    onValueChange = { onBpmChange(quantizeBpm(it.toDouble())) },
                    valueRange = 40f..240f,
                    colors = SliderDefaults.colors(
                        thumbColor = scheme.primary,
                        activeTrackColor = scheme.primary,
                        inactiveTrackColor = scheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                FilledTonalIconButton(
                    onClick = { onBpmChange(quantizeBpm(bpm + 1.0)) },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Add, contentDescription = "Faster") }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fine",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { onBpmChange(quantizeBpm(bpm - 0.1)) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text("−0.1", style = MaterialTheme.typography.labelMedium) }
                FilledTonalButton(
                    onClick = { onBpmChange(quantizeBpm(bpm + 0.1)) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text("+0.1", style = MaterialTheme.typography.labelMedium) }
            }

            Spacer(Modifier.height(20.dp))

            // Time signature
            Text("TIME SIGNATURE", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 2..8) {
                    FilterChip(
                        selected = n == beatsPerBar,
                        onClick = { onBeatsChange(n) },
                        label = { Text("$n") },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // First beat (when the click starts, aligned to the recording's downbeat)
            val offsetMs = beatOffsetFrames / FramesPerMs
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FIRST BEAT", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    "$offsetMs ms",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onBeatOffsetChange((beatOffsetFrames - 10L * FramesPerMs).coerceAtLeast(0L)) },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Remove, contentDescription = "Earlier") }
                Slider(
                    value = offsetMs.toFloat().coerceIn(0f, 3000f),
                    onValueChange = { onBeatOffsetChange(it.roundToInt().toLong() * FramesPerMs) },
                    valueRange = 0f..3000f,
                    colors = SliderDefaults.colors(
                        thumbColor = scheme.primary,
                        activeTrackColor = scheme.primary,
                        inactiveTrackColor = scheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                FilledTonalIconButton(
                    onClick = { onBeatOffsetChange(beatOffsetFrames + 10L * FramesPerMs) },
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Add, contentDescription = "Later") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "When the click starts. Steppers nudge ±10 ms to line it up with the recording's first beat.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // Detected reference
            if (detectedBpm != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-detected", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${formatBpm(detectedBpm)} bpm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                            )
                        }
                        TextButton(onClick = { onBpmChange(detectedBpm) }) {
                            Text("Use")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save to track")
            }
        }
    }
}
