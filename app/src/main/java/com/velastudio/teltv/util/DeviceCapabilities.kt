package com.velastudio.teltv.util

import android.app.ActivityManager
import android.content.Context

/**
 * Every tuning knob in the app that trades memory for smoothness reads from here, instead of
 * hard-coding "good enough on my dev box" numbers. Cheap Android TV sticks/dongles report as
 * `isLowRamDevice = true` (Android treats anything <= ~1GB that way) and separately many boxes
 * that ship with 2GB report `false` there but still have a tiny `memoryClass` -- so we use both
 * signals, not just the one flag, and fall back to the conservative profile whenever we're not
 * sure. Better to under-use a capable box than to OOM-kill a cheap one mid-scroll.
 */
object DeviceCapabilities {

    data class Profile(
        val isConstrained: Boolean,
        /** Items fetched per page when paginating a channel's video list. */
        val pageSize: Int,
        /** How many items ahead Paging3 is allowed to prefetch. */
        val prefetchDistance: Int,
        /** Grid columns in the video browse screen. */
        val gridColumns: Int,
        /** Max in-memory bitmap cache for thumbnails, in bytes. */
        val imageMemoryCacheBytes: Long,
        /** Thumbnails are decoded/downloaded no larger than this on the long edge (px). */
        val thumbnailMaxDimensionPx: Int,
        /** Debounce window for search-as-you-type, in ms -- longer on weak devices. */
        val searchDebounceMs: Long
    )

    private val CONSTRAINED = Profile(
        isConstrained = true,
        pageSize = 100,
        prefetchDistance = 60,
        gridColumns = 3,
        imageMemoryCacheBytes = 12L * 1024 * 1024,
        thumbnailMaxDimensionPx = 200,
        searchDebounceMs = 450L
    )

    private val NORMAL = Profile(
        isConstrained = false,
        pageSize = 100,
        prefetchDistance = 50,
        gridColumns = 5,
        imageMemoryCacheBytes = 48L * 1024 * 1024,
        thumbnailMaxDimensionPx = 320,
        searchDebounceMs = 250L
    )

    private var cached: Profile? = null

    fun profile(context: Context): Profile = cached ?: compute(context).also { cached = it }

    private fun compute(context: Context): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return CONSTRAINED

        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        // isLowRamDevice covers the very cheapest sticks; totalRamMb/memoryClass catches the
        // common "2GB total, ~3GB usable after system" Android TV box that Android itself
        // doesn't flag as low-RAM but that will absolutely stutter loading a 2,000-item grid
        // with full-size thumbnails and no paging.
        val constrained = am.isLowRamDevice || totalRamMb in 1..2560 || am.memoryClass <= 96

        return if (constrained) CONSTRAINED else NORMAL
    }
}
