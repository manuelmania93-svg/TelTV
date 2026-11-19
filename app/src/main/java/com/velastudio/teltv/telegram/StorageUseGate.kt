package com.velastudio.teltv.telegram

/** Coordinates only idle/manual maintenance. Playback's existing emergency recovery is separate. */
class StorageUseGate {
    private val lock = java.lang.Object()
    private var cleaning = false
    private var readers = 0
    private var players = 0
    private val pending = mutableMapOf<Int, Int>()
    private val active = mutableSetOf<Int>()
    private val queued = mutableListOf<Pair<Int, () -> Unit>>()

    fun playerOpened() = synchronized(lock) { players++ }
    fun playerClosed() = synchronized(lock) { players = (players - 1).coerceAtLeast(0) }

    /** Called on the media loader thread, held across open/read/close, even for cached files. */
    fun acquireReader() = synchronized(lock) {
        while (cleaning) lock.wait()
        readers++
    }
    fun releaseReader() = synchronized(lock) { readers = (readers - 1).coerceAtLeast(0) }

    fun submitDownload(id: Int, send: () -> Unit) {
        val now = synchronized(lock) {
            if (cleaning) { queued += id to send; false }
            else { pending[id] = (pending[id] ?: 0) + 1; true }
        }
        if (now) send()
    }
    fun downloadReply(id: Int, isActive: Boolean) = synchronized(lock) {
        val left = (pending[id] ?: 1) - 1
        if (left <= 0) pending.remove(id) else pending[id] = left
        if (isActive) active.add(id) else active.remove(id)
        Unit
    }
    fun fileUpdate(id: Int, isActive: Boolean) = synchronized(lock) {
        if (isActive) active.add(id) else active.remove(id)
        Unit
    }
    fun tryBeginCleanup(): Boolean = synchronized(lock) {
        if (cleaning || readers > 0 || players > 0 || pending.isNotEmpty() || active.isNotEmpty()) false
        else { cleaning = true; true }
    }
    fun endCleanup() {
        val starts = synchronized(lock) {
            check(cleaning)
            cleaning = false
            val starts = queued.toList()
            queued.clear()
            starts.forEach { (id, _) -> pending[id] = (pending[id] ?: 0) + 1 }
            lock.notifyAll()
            starts
        }
        starts.forEach { (_, send) -> send() }
    }

    companion object { val shared = StorageUseGate() }
}
