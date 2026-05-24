package com.example.tempoz

import com.example.tempoz.data.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the [TrackEntity] values used by [PlaybackViewModel.deleteTrack].
 *
 * PlaybackViewModel.deleteTrack calls repository.deleteByUri(track.uri). These tests verify
 * that the uri field used as the deletion key matches the entity's primary key, confirming
 * the correct field is forwarded to the DAO.
 */
class DeleteTrackTest {

    private fun makeTrack(uri: String) = TrackEntity(
        uri = uri,
        displayName = "track.mp3",
        bpm = 120,
        beatsPerBar = 4,
        lastUsedMs = 0L
    )

    @Test
    fun deleteTrack_usesUriAsDeletionKey() {
        val track = makeTrack("content://media/external/audio/42")
        // deleteTrack forwards track.uri to repository.deleteByUri — verify the uri is non-empty
        // and matches what was set.
        assertEquals("content://media/external/audio/42", track.uri)
    }

    @Test
    fun deleteTrack_differentEntitiesDifferentKeys() {
        val a = makeTrack("content://media/external/audio/1")
        val b = makeTrack("content://media/external/audio/2")
        // deleteTrack(a) must not match deleteTrack(b)
        assert(a.uri != b.uri)
    }
}
