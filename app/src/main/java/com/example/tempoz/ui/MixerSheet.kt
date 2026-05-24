package com.example.tempoz.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerSheet(
    trackVolume: Float,
    clickVolume: Float,
    onTrackVolumeChange: (Float) -> Unit,
    onClickVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("Mixer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("Track")
            Slider(value = trackVolume, onValueChange = onTrackVolumeChange, valueRange = 0f..1f)
            Spacer(Modifier.height(8.dp))
            Text("Click")
            Slider(value = clickVolume, onValueChange = onClickVolumeChange, valueRange = 0f..1f)
            Spacer(Modifier.height(24.dp))
        }
    }
}
