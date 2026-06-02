package com.example.tempoz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tempoz.data.TrackEntity
import com.example.tempoz.formatBpm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackExplorerSheet(
    tracks: List<TrackEntity>,
    currentUri: String?,
    onSelectTrack: (TrackEntity) -> Unit,
    onDeleteTrack: (TrackEntity) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
        ) {
            SheetTitle("Library", subtitle = if (tracks.isEmpty()) "No tracks yet" else "${tracks.size} track${if (tracks.size == 1) "" else "s"}")

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import track")
            }
            Spacer(Modifier.height(12.dp))

            if (tracks.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                ) {
                    Text(
                        "Imported tracks appear here.\nSwipe a track to remove it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tracks, key = { it.uri }) { track ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                onDeleteTrack(track)
                            }
                        }
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 2.dp)
                                        .background(scheme.errorContainer, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Delete",
                                        tint = scheme.onErrorContainer,
                                    )
                                }
                            },
                        ) {
                            TrackRow(
                                track = track,
                                isCurrent = track.uri == currentUri,
                                onClick = {
                                    onSelectTrack(track)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isCurrent) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(scheme.surfaceContainerLowest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isCurrent) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatBpm(track.bpm)} bpm · ${track.beatsPerBar}/4",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (isCurrent) {
                Text(
                    "now",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
    }
}
