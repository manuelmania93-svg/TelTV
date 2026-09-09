package com.velastudio.teltv.util

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Grab-bag of small things that make D-pad/remote navigation feel smooth instead of janky --
 * none of these are individually clever, but skipping any one of them is exactly the kind of
 * thing that makes an app feel like a website ported to a TV rather than a TV app:
 *
 * - [rememberKeyRepeatThrottle]: TV remotes auto-repeat DPAD events while held, much faster than
 *   a person can distinguish; without throttling, a long-press on "skip forward" or "scroll down"
 *   fires far more recompositions/seeks than intended and visibly stutters. This coalesces
 *   repeats within a short window into a single action, then lets them through at a steady rate.
 * - [rememberDebounced]: for search-as-you-type, waits for typing to actually pause before
 *   firing a query -- device-tuned via [DeviceCapabilities] so a weak box gets a longer pause.
 * - [LazyGridState.isNearEnd] / [LazyListState.isNearEnd]: the standard "close enough to the
 *   bottom to start loading the next page" check, shared so browse/search/home rows all use the
 *   same threshold instead of each screen inventing its own.
 */

@Composable
fun rememberKeyRepeatThrottle(minIntervalMs: Long = 90L): (() -> Unit) -> Unit {
    var lastFireMs by remember { mutableStateOf(0L) }
    return remember {
        { action: () -> Unit ->
            val now = System.currentTimeMillis()
            if (now - lastFireMs >= minIntervalMs) {
                lastFireMs = now
                action()
            }
        }
    }
}

@Composable
fun <T> rememberDebounced(delayMs: Long, key: T, onSettled: suspend (T) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    var pendingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    return remember(key) {
        {
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(delayMs)
                onSettled(key)
            }
        }
    }
}

/** True once the last fully-visible item is within [threshold] items of the end of the list. */
fun LazyGridState.isNearEnd(threshold: Int): Boolean {
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= totalItems - 1 - threshold
}

fun LazyListState.isNearEnd(threshold: Int): Boolean {
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return false
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= totalItems - 1 - threshold
}
