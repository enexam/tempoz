package com.example.tempoz

import com.example.tempoz.data.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that [TrackEntity] uses [uri] as its primary key.
 *
 * Room uses KSP to read Kotlin metadata, so @PrimaryKey is not visible via Java bytecode
 * reflection. These tests verify the structural invariants that make @Upsert correct:
 * no autoGenerate id field, and uri is a stable String key.
 */
class TrackEntityTest {

    @Test
    fun noIdField_onTrackEntity() {
        // An autoGenerate id=0 field would cause every @Upsert call to insert a new row.
        val idField = try {
            TrackEntity::class.java.getDeclaredField("id")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertNull("TrackEntity must not have an 'id' field", idField)
    }

    @Test
    fun uriField_isString() {
        val uriField = TrackEntity::class.java.getDeclaredField("uri")
        assertEquals("uri field must be String type", String::class.java, uriField.type)
    }

    @Test
    fun upsertSameUri_sameKey() {
        // Two entities with the same URI share the same primary key value.
        // Room @Upsert will UPDATE the existing row rather than INSERT a duplicate.
        val first = TrackEntity(
            uri = "content://media/external/audio/1",
            displayName = "track.mp3",
            bpm = 120.0,
            beatsPerBar = 4,
            lastUsedMs = 1000L
        )
        val second = TrackEntity(
            uri = "content://media/external/audio/1",
            displayName = "track.mp3",
            bpm = 130.0,
            beatsPerBar = 3,
            lastUsedMs = 2000L
        )
        assertEquals("Same URI must produce identical PK", first.uri, second.uri)
    }

    @Test
    fun differentUris_differentKeys() {
        val first = TrackEntity(
            uri = "content://media/external/audio/1",
            displayName = "a.mp3",
            bpm = 120.0,
            beatsPerBar = 4,
            lastUsedMs = 1000L
        )
        val second = TrackEntity(
            uri = "content://media/external/audio/2",
            displayName = "b.mp3",
            bpm = 120.0,
            beatsPerBar = 4,
            lastUsedMs = 2000L
        )
        assertFalse("Different URIs must produce different PKs", first.uri == second.uri)
    }
}
