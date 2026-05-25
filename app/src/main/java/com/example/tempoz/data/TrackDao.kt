package com.example.tempoz.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("SELECT * FROM tracks ORDER BY last_used_ms DESC")
    fun getAllByLastUsed(): Flow<List<TrackEntity>>

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
