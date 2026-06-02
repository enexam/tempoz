package com.example.tempoz

import java.util.Locale
import kotlin.math.roundToInt

/** Formats [bpm] to one decimal place, trimming a trailing ".0". */
fun formatBpm(bpm: Double): String {
    val q = quantizeBpm(bpm)
    return if (q == q.toLong().toDouble()) {
        q.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", q)
    }
}

/**
 * Rounds [bpm] to the nearest 0.1 and clamps to 40.0–240.0.
 * "Round to 0.1" means `(x * 10).roundToInt() / 10.0`.
 */
fun quantizeBpm(bpm: Double): Double {
    return ((bpm * 10).roundToInt() / 10.0).coerceIn(40.0, 240.0)
}
