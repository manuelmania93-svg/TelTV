package com.velastudio.teltv.telegram

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One manual clear (including its size refresh) at a time across Home and Settings.
 * Does not choose files, clear app data, or change automatic cleanup policy.
 * The application owns this gate so navigating/recreating a screen cannot bypass it.
 */
class ManualCacheClear {
    private val claimed = AtomicBoolean(false)
    private val mutableClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = mutableClearing.asStateFlow()

    fun tryLaunch(scope: CoroutineScope, action: suspend () -> Unit): Boolean {
        // Claim before scheduling: repeated clicks in the same frame cannot queue clears.
        if (!claimed.compareAndSet(false, true)) return false
        mutableClearing.value = true
        val job = scope.launch { action() }
        // Also runs if the scope was already cancelled and the body never starts.
        job.invokeOnCompletion {
            mutableClearing.value = false
            claimed.set(false)
        }
        return true
    }
}
