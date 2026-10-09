package com.velastudio.teltv.util.matcher

/**
 * Tiny content fingerprint: size + 64-bit hash of three 64 KB samples (head, middle, tail).
 * Reads at most 192 KB through an 8 KB buffer, on demand only. Two copies of the same upload
 * in different channels get the same fingerprint even when their names differ.
 */
object ContentFingerprint {
    const val SAMPLE_BYTES = 64 * 1024
    private const val BUF = 8 * 1024
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    /** [readAt] fills the buffer from [offset] and returns bytes read (or <= 0 at end). */
    fun compute(size: Long, readAt: (offset: Long, buf: ByteArray, len: Int) -> Int): String {
        var h = FNV_OFFSET xor size
        h *= FNV_PRIME
        val offsets = if (size <= SAMPLE_BYTES * 3L) listOf(0L)
        else listOf(0L, size / 2 - SAMPLE_BYTES / 2, size - SAMPLE_BYTES)
        val span = if (size <= SAMPLE_BYTES * 3L) size else SAMPLE_BYTES.toLong()
        val buf = ByteArray(BUF)
        for (start in offsets) {
            var pos = start
            val end = start + span
            while (pos < end) {
                val want = minOf(BUF.toLong(), end - pos).toInt()
                val n = readAt(pos, buf, want)
                if (n <= 0) break
                for (i in 0 until n) { h = (h xor (buf[i].toLong() and 0xff)) * FNV_PRIME }
                pos += n
            }
            h = (h xor start) * FNV_PRIME
        }
        return "%d-%016x".format(size, h)
    }

    /** Groups ids that share a fingerprint; singletons are dropped. */
    fun duplicates(fingerprints: Map<String, String>): List<List<String>> =
        fingerprints.entries.groupBy({ it.value }, { it.key }).values.filter { it.size > 1 }
}
