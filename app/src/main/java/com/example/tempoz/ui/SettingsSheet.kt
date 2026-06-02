package com.example.tempoz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.tempoz.BuildConfig
import com.example.tempoz.data.AppSettings
import kotlin.math.roundToInt

private val SubdivisionOptions = listOf(
    1 to "Quarter",
    2 to "Eighth",
    3 to "Triplet",
    4 to "Sixteenth",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onDefaultTrackVolumeChange: (Float) -> Unit,
    onDefaultClickVolumeChange: (Float) -> Unit,
    onSubdivisionChange: (Int) -> Unit,
    onGhostVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            SheetTitle("Settings", subtitle = "Global defaults for every session")
            Spacer(Modifier.height(20.dp))

            // ---- Mixer defaults ----
            Text(
                text = "Default volumes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SettingsVolumeRow(
                icon = Icons.Rounded.LibraryMusic,
                label = "Track",
                value = settings.defaultTrackVolume,
                onValueChange = onDefaultTrackVolumeChange,
            )
            Spacer(Modifier.height(18.dp))
            SettingsVolumeRow(
                icon = Icons.Rounded.GraphicEq,
                label = "Click",
                value = settings.defaultClickVolume,
                onValueChange = onDefaultClickVolumeChange,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // ---- Subdivision ----
            Text(
                text = "Subdivision",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubdivisionOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = settings.subdivision == value,
                        onClick = { onSubdivisionChange(value) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SettingsVolumeRow(
                icon = Icons.Rounded.GraphicEq,
                label = "Ghost volume",
                value = settings.ghostVolume,
                onValueChange = onGhostVolumeChange,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // ---- About ----
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tempoz",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsVolumeRow(
    icon: ImageVector,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceVariant,
            ),
        )
    }
}
