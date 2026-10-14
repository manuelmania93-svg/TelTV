package com.velastudio.teltv.player

/**
 * Pure decisions for the playback-time storage guard ([PlaybackStorageGuard]), kept free of
 * Android imports so the whole decision table is unit-testable on the JVM.
 *
 * Why this exists: videos stream from Telegram through TDLib, which writes every watched byte
 * into its on-disk files directory (`filesDir/tdlib-files`) and keeps it there as a cache. The
 * hourly [com.velastudio.teltv.worker.CacheTrimWorker] is far too coarse for a TV box with only
 * ~4 GB free: a couple of long episodes (e.g. 480p wrestling at ~1 GB each) fill the disk
 * between trims, TDLib can no longer write the next chunk, and playback stalls.
 *
 * Two escalation levels:
 *  - [Action.EVICT_OLD_FILES]: free space dropped below [SOFT_FREE_BYTES]. Ask TDLib to delete
 *    every cached file it has not touched in the last [EVICT_IMMUNITY_SECONDS] seconds. The file
 *    currently being played is downloaded continuously while it plays, so it stays immune while
 *    anything watched in an earlier session is fair game ("oldest cached videos first").
 *  - [Action.EMERGENCY_CLEAR_AND_RESUME]: free space dropped below [CRITICAL_FREE_BYTES] and a
 *    plain eviction either already happened or cannot keep up (a single file bigger than the
 *    free space). The guard pauses, persists the position, cancels the download, wipes
 *    everything wipeable and resumes from the saved position; the stream then re-downloads
 *    forward from that offset instead of holding the whole prefix on disk.
 *
 * Both actions are rate-limited: TDLib's OptimizeStorage is a heavy disk scan, and a repeated
 * emergency usually means the file genuinely does not fit, in which case looping would just
 * stutter playback forever.
 */
object StorageGuardPolicy {

    /** Below this much free space the guard starts evicting old cached videos. */
    const val SOFT_FREE_BYTES = 750L * 1024 * 1024

    /** Below this much free space the guard does a full clear-and-resume of the current video. */
    const val CRITICAL_FREE_BYTES = 300L * 1024 * 1024

    /**
     * Files TDLib touched within this many seconds survive an eviction. Playing video is
     * re-downloaded chunk by chunk the whole time it plays, so 10 minutes protects the current
     * file (and a recently played previous one) while making anything from an earlier session
     * evictable. Hypothesis, not verified on device: TDLib's OptimizeStorage treats a file's
     * last-download time as its last-use time and never deletes a file with an active download.
     */
    const val EVICT_IMMUNITY_SECONDS = 600

    /** OptimizeStorage at most this often. */
    const val MIN_EVICT_INTERVAL_MS = 120_000L

    /** Full clear-and-resume at most this often, so a non-recovering file cannot loop. */
    const val MIN_EMERGENCY_INTERVAL_MS = 300_000L

    /** How often the guard re-checks free space. */
    const val CHECK_INTERVAL_MS = 15_000L

    /** How long the emergency path waits for freed space to show up before resuming anyway. */
    const val RESUME_WAIT_MS = 15_000L

    /** Free space the emergency path wants to see before it stops waiting early. */
    const val RECOVERED_FREE_BYTES = 450L * 1024 * 1024

    enum class Action { NONE, EVICT_OLD_FILES, EMERGENCY_CLEAR_AND_RESUME }

    fun decideAction(
        freeBytes: Long,
        lastEvictAtMs: Long,
        lastEmergencyAtMs: Long,
        nowMs: Long
    ): Action {
        if (freeBytes >= SOFT_FREE_BYTES) return Action.NONE
        if (freeBytes < CRITICAL_FREE_BYTES && nowMs - lastEmergencyAtMs >= MIN_EMERGENCY_INTERVAL_MS) {
            return Action.EMERGENCY_CLEAR_AND_RESUME
        }
        if (nowMs - lastEvictAtMs >= MIN_EVICT_INTERVAL_MS) {
            return Action.EVICT_OLD_FILES
        }
        return Action.NONE
    }
}
