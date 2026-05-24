package com.example.tempoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tempoz.PlaybackState
import com.example.tempoz.PlaybackViewModel

@Composable
fun PlayerScreen(
    viewModel: PlaybackViewModel,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileName by viewModel.fileName.collectAsState()
    val fileUri by viewModel.fileUri.collectAsState()
    val bpm by viewModel.bpm.collectAsState()
    val beatsPerBar by viewModel.beatsPerBar.collectAsState()
    val trackVolume by viewModel.trackVolume.collectAsState()
    val clickVolume by viewModel.clickVolume.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    // Local text state for BPM field to allow free typing before committing
    var bpmText by remember(bpm) { mutableStateOf(bpm.toString()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // File pick button
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text(text = fileName)
        }

        // BPM row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = {
                val next = (bpm - 1).coerceIn(40, 240)
                viewModel.setBpm(next)
                bpmText = next.toString()
            }) {
                Text("-")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = bpmText,
                onValueChange = { input ->
                    // Reject non-numeric characters
                    val filtered = input.filter { it.isDigit() }
                    bpmText = filtered
                    val parsed = filtered.toIntOrNull()
                    if (parsed != null) {
                        viewModel.setBpm(parsed.coerceIn(40, 240))
                    }
                },
                label = { Text("BPM") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val next = (bpm + 1).coerceIn(40, 240)
                viewModel.setBpm(next)
                bpmText = next.toString()
            }) {
                Text("+")
            }
        }

        // Beats per bar row
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (n in 2..8) {
                FilterChip(
                    selected = (n == beatsPerBar),
                    onClick = { viewModel.setBeatsPerBar(n) },
                    label = { Text(n.toString()) }
                )
            }
        }

        // Track volume slider
        Text(text = "Track")
        Slider(
            value = trackVolume,
            onValueChange = { viewModel.setTrackVolume(it) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        // Click volume slider
        Text(text = "Click")
        Slider(
            value = clickVolume,
            onValueChange = { viewModel.setClickVolume(it) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Restart + play/pause button row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Restart button
            IconButton(
                onClick = { viewModel.restart() },
                enabled = fileUri != null
            ) {
                Icon(
                    imageVector = Icons.Filled.Replay,
                    contentDescription = "Restart"
                )
            }

            // Play/pause toggle button
            FilledIconButton(
                onClick = {
                    when (playbackState) {
                        PlaybackState.STOPPED -> viewModel.play()
                        PlaybackState.PLAYING -> viewModel.pause()
                        PlaybackState.PAUSED  -> viewModel.resume()
                    }
                },
                enabled = fileUri != null
            ) {
                Icon(
                    imageVector = if (playbackState == PlaybackState.PLAYING)
                        Icons.Filled.Pause
                    else
                        Icons.Filled.PlayArrow,
                    contentDescription = if (playbackState == PlaybackState.PLAYING) "Pause" else "Play"
                )
            }
        }
    }
}
