package com.example.tempoz.data

import kotlinx.coroutines.flow.Flow

class TrackRepository(private val dao: TrackDao) {
    val tracks: Flow<List<TrackEntity>> = dao.getAllByLastUsed()
    suspend fun upsert(track: TrackEntity) = dao.upsert(track)
    suspend fun deleteByUri(uri: String) = dao.deleteByUri(uri)
}
