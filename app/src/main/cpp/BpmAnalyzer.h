#pragma once

#include <cstdint>

/**
 * Result of a BPM analysis pass.
 * bpm == 0 and firstBeatFrames == 0 indicates failure or a file too short to analyze.
 */
struct BpmResult {
    int     bpm;
    int64_t firstBeatFrames;
};

/**
 * Stateless BPM analyzer.
 *
 * Opens its own AMediaExtractor + AMediaCodec independently of the playback
 * FileDecoder so that analysis can be performed without disrupting playback.
 */
class BpmAnalyzer {
public:
    /**
     * Analyze the audio file identified by [fd, offset, length].
     *
     * Decodes up to 30 s of audio at the file's native sample rate, computes
     * an onset-strength envelope, autocorrelates it over the BPM range 40–240,
     * and detects the first beat position.
     *
     * @param fd     file descriptor (may be closed after this call returns)
     * @param offset byte offset of the data source within fd
     * @param length byte length of the data source
     * @return BpmResult with detected BPM and first-beat frame offset,
     *         or {0, 0} on failure.
     */
    BpmResult analyze(int fd, int64_t offset, int64_t length);
};
