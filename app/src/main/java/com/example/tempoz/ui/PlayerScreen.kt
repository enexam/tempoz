package com.example.tempoz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tempoz.ClickSoundOptions
import com.example.tempoz.PlaybackState
import com.example.tempoz.PlaybackViewModel
import com.example.tempoz.formatBpm
import com.example.tempoz.quantizeBpm

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
    val beatOffsetFrames by viewModel.beatOffsetFrames.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val hasTrack = fileUri != null
    val isPlaying = playbackState == PlaybackState.PLAYING
    val currentEntity = remember(tracks, fileUri) {
        tracks.find { it.uri == fileUri?.toString() }
    }

    var showLibrary by remember { mutableStateOf(false) }
    var showTrackSettings by remember { mutableStateOf(false) }
    var showClick by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // UI-only state for features without an audio backend yet.
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1.0f) }

    val scheme = MaterialTheme.colorScheme

    fun nudgeBpm(delta: Double) {
        viewModel.setBpm(quantizeBpm(bpm + delta))
    }

    fun scaleBpm(factor: Double) {
        viewModel.setBpm(quantizeBpm(bpm * factor))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- Top bar : wordmark + analysis status ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(scheme.primary, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Tempoz",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "analyzing",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // ---- Centerpiece + meta (flexible, centered) ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // − / Pulse Core / + : the steppers live in the room beside the ring.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { nudgeBpm(-1.0) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Slower")
                }
                PulseCore(
                    bpm = bpm,
                    beatsPerBar = beatsPerBar,
                    isPlaying = isPlaying,
                    firstBeatOffsetFrames = beatOffsetFrames,
                    audiblePositionMs = { viewModel.audiblePositionMs() },
                    onTap = { viewModel.tapTempo() },
                    ringSize = 196.dp,
                )
                FilledTonalIconButton(onClick = { nudgeBpm(+1.0) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "Faster")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Fine ±0.1 BPM steppers.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { nudgeBpm(-0.1) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text("−0.1", style = MaterialTheme.typography.labelMedium) }
                FilledTonalButton(
                    onClick = { nudgeBpm(+0.1) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text("+0.1", style = MaterialTheme.typography.labelMedium) }
            }

            Spacer(Modifier.height(8.dp))

            // Quick tempo ratios: half / dotted / dotted / double.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TempoFactorButton("÷2") { scaleBpm(0.5) }
                TempoFactorButton("÷1.5") { scaleBpm(1.0 / 1.5) }
                TempoFactorButton("×1.5") { scaleBpm(1.5) }
                TempoFactorButton("×2") { scaleBpm(2.0) }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (hasTrack) fileName else "No track loaded",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasTrack) {
                    "${formatBpm(bpm)} bpm · $beatsPerBar/4" +
                        if (currentEntity?.detectedBpm != null) " · auto-detected" else ""
                } else {
                    "Import a track to begin"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // ---- Seek ----
        Column(modifier = Modifier.fillMaxWidth()) {
            val sliderValue = if (isDragging) dragValue
            else (currentPositionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat())
            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { isDragging = true; dragValue = it },
                onValueChangeFinished = {
                    viewModel.seekTo((dragValue * durationMs).toLong())
                    isDragging = false
                },
                valueRange = 0f..1f,
                enabled = durationMs > 0L,
                colors = SliderDefaults.colors(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = scheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(currentPositionMs), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text("-" + formatTime((durationMs - currentPositionMs).coerceAtLeast(0L)), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Transport ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.prevTrack() }, enabled = tracks.size > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { viewModel.restart() }, enabled = hasTrack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Replay, contentDescription = "Restart", modifier = Modifier.size(26.dp))
            }
            FilledIconButton(
                onClick = {
                    when (playbackState) {
                        PlaybackState.STOPPED -> viewModel.play()
                        PlaybackState.PLAYING -> viewModel.pause()
                        PlaybackState.PAUSED -> viewModel.resume()
                    }
                },
                enabled = hasTrack,
                modifier = Modifier.size(74.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { viewModel.nextTrack() }, enabled = tracks.size > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { viewModel.toggleLoop() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Repeat,
                    contentDescription = if (loopMode) "Loop on" else "Loop off",
                    tint = if (loopMode) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Quick-access dock ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DockButton(Icons.Rounded.LibraryMusic, "Tracks", Modifier.weight(1f)) { showLibrary = true }
            DockButton(Icons.Rounded.Tune, "Track", Modifier.weight(1f), enabled = hasTrack) { showTrackSettings = true }
            DockButton(Icons.Rounded.MusicNote, "Click", Modifier.weight(1f)) { showClick = true }
            DockButton(Icons.Rounded.GraphicEq, "Mixer", Modifier.weight(1f)) { showMixer = true }
            DockButton(Icons.Rounded.Speed, "Speed", Modifier.weight(1f)) { showSpeed = true }
        }
    }

    if (showLibrary) {
        TrackExplorerSheet(
            tracks = tracks,
            currentUri = fileUri?.toString(),
            onSelectTrack = { viewModel.selectTrack(it) },
            onDeleteTrack = { viewModel.deleteTrack(it) },
            onImport = onPickFile,
            onDismiss = { showLibrary = false },
        )
    }
    if (showTrackSettings) {
        TrackSettingsSheet(
            trackName = if (hasTrack) fileName else null,
            bpm = bpm,
            beatsPerBar = beatsPerBar,
            detectedBpm = currentEntity?.detectedBpm,
            beatOffsetFrames = beatOffsetFrames,
            onBpmChange = { viewModel.setBpm(it) },
            onBeatsChange = { viewModel.setBeatsPerBar(it) },
            onBeatOffsetChange = { viewModel.setBeatOffsetFrames(it) },
            onSave = { viewModel.saveCurrentTrackParams() },
            onDismiss = { showTrackSettings = false },
        )
    }
    if (showClick) {
        ClickSoundSheet(
            selected = settings.clickSound,
            options = ClickSoundOptions,
            onSelect = { viewModel.setClickSound(it) },
            onDismiss = { showClick = false },
        )
    }
    if (showMixer) {
        MixerSheet(
            trackVolume = trackVolume,
            clickVolume = clickVolume,
            onTrackVolumeChange = { viewModel.setTrackVolume(it) },
            onClickVolumeChange = { viewModel.setClickVolume(it) },
            onDismiss = { showMixer = false },
        )
    }
    if (showSpeed) {
        SpeedSheet(
            speed = playbackSpeed,
            onSpeedChange = { playbackSpeed = it },
            onDismiss = { showSpeed = false },
        )
    }
    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onDefaultTrackVolumeChange = { viewModel.setDefaultTrackVolume(it) },
            onDefaultClickVolumeChange = { viewModel.setDefaultClickVolume(it) },
            onSubdivisionChange = { viewModel.setSubdivision(it) },
            onGhostVolumeChange = { viewModel.setGhostVolume(it) },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun TempoFactorButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
