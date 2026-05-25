package com.example.tempoz.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "bpm") val bpm: Int,
    @ColumnInfo(name = "beats_per_bar") val beatsPerBar: Int,
    @ColumnInfo(name = "last_used_ms") val lastUsedMs: Long,
    @ColumnInfo(name = "detected_bpm") val detectedBpm: Int? = null,
    @ColumnInfo(name = "beat_offset_frames") val beatOffsetFrames: Long? = null
)
