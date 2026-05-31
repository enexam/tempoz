package com.example.tempoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.tempoz.PlaybackState
import com.example.tempoz.PlaybackViewModel
import androidx.compose.material3.MaterialTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    val tracks by viewModel.tracks.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val loopMode by viewModel.loopMode.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()

    var showTrackExplorer by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // Local text state for BPM field to allow free typing before committing.
    // Displayed rounded; the engine still plays the fractional detected tempo
    // until the user edits it (manual edits are integer-valued).
    var bpmText by remember(bpm) { mutableStateOf(bpm.roundToInt().toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Track name with analysis indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (fileUri == null) "No track loaded" else fileName,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .alpha(if (isAnalyzing) 1f else 0f),
                strokeWidth = 2.dp
            )
        }

        // Seek section
        Column(modifier = Modifier.fillMaxWidth()) {
            val sliderValue = if (isDragging) dragValue
                else (currentPositionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat())
            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    isDragging = true
                    dragValue = newValue
                },
                onValueChangeFinished = {
                    viewModel.seekTo((dragValue * durationMs).toLong())
                    isDragging = false
                },
                valueRange = 0f..1f,
                enabled = durationMs > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val elapsedSec = (currentPositionMs / 1000L).toInt()
                val remainingSec = ((durationMs - currentPositionMs).coerceAtLeast(0L) / 1000L).toInt()
                Text(
                    text = "${elapsedSec / 60}:${(elapsedSec % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "-${remainingSec / 60}:${(remainingSec % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // BPM row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = {
                val next = (bpm.roundToInt() - 1).coerceIn(40, 240)
                viewModel.setBpm(next.toDouble())
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
                        viewModel.setBpm(parsed.coerceIn(40, 240).toDouble())
                    }
                },
                label = { Text("BPM") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val next = (bpm.roundToInt() + 1).coerceIn(40, 240)
                viewModel.setBpm(next.toDouble())
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

        Spacer(modifier = Modifier.weight(1f))

        // Play controls row: [prev] [restart] [play/pause] [next]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prev track button
            FilledTonalIconButton(
                onClick = { viewModel.prevTrack() },
                enabled = tracks.size > 1,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track")
            }
            Spacer(Modifier.width(8.dp))
            // Restart button
            FilledTonalIconButton(
                onClick = { viewModel.restart() },
                enabled = fileUri != null,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Replay, contentDescription = "Restart")
            }
            Spacer(Modifier.width(8.dp))
            // Play/pause button
            FilledIconButton(
                onClick = {
                    when (playbackState) {
                        PlaybackState.STOPPED -> viewModel.play()
                        PlaybackState.PLAYING -> viewModel.pause()
                        PlaybackState.PAUSED  -> viewModel.resume()
                    }
                },
                enabled = fileUri != null,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (playbackState == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playbackState == PlaybackState.PLAYING) "Pause" else "Play",
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            // Next track button
            FilledTonalIconButton(
                onClick = { viewModel.nextTrack() },
                enabled = tracks.size > 1,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next track")
            }
        }

        // Loop toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loopMode) {
                FilledTonalIconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Repeat, contentDescription = "Loop on")
                }
            } else {
                OutlinedIconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Filled.Repeat, contentDescription = "Loop off")
                }
            }
        }

        // Bottom action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            OutlinedButton(onClick = { showTrackExplorer = true }) {
                Icon(Icons.Filled.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tracks")
            }
            OutlinedButton(onClick = { showMixer = true }) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mixer")
            }
        }
    }

    if (showTrackExplorer) {
        TrackExplorerSheet(
            tracks = tracks,
            onSelectTrack = { viewModel.selectTrack(it) },
            onDeleteTrack = { viewModel.deleteTrack(it) },
            onImport = onPickFile,
            onDismiss = { showTrackExplorer = false }
        )
    }

    if (showMixer) {
        MixerSheet(
            trackVolume = trackVolume,
            clickVolume = clickVolume,
            onTrackVolumeChange = { viewModel.setTrackVolume(it) },
            onClickVolumeChange = { viewModel.setClickVolume(it) },
            onDismiss = { showMixer = false }
        )
    }
}
