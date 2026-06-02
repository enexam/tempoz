package com.example.tempoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.tempoz.formatBpm
import kotlin.math.abs
import kotlin.math.roundToInt

private val SpeedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    bpm: Double,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
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
            SheetTitle("Playback speed", subtitle = "Stretch the track without changing pitch")
            Spacer(Modifier.height(20.dp))

            Text(
                text = formatSpeed(speed) + "×",
                style = MaterialTheme.typography.displaySmall,
                color = scheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))

            // Show the resulting effective BPM at this speed.
            Text(
                text = "${formatBpm(bpm * speed)} bpm",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Slider(
                value = speed,
                onValueChange = { onSpeedChange((it * 20f).roundToInt() / 20f) },
                valueRange = 0.5f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = scheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                SpeedPresets.forEach { preset ->
                    FilterChip(
                        selected = abs(preset - speed) < 0.001f,
                        onClick = { onSpeedChange(preset) },
                        label = { Text(formatSpeed(preset) + "×") },
                    )
                }
            }
        }
    }
}

private fun formatSpeed(value: Float): String {
    // Trim trailing zeros: 1.0 -> "1", 1.25 -> "1.25", 0.5 -> "0.5"
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
}
