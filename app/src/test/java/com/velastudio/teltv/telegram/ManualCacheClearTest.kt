package com.velastudio.teltv.telegram

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ManualCacheClearTest {
    @Test fun rapidClicksFromBothScreensStartOnlyOneAction() = runBlocking {
        val gate = ManualCacheClear()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        assertTrue(gate.tryLaunch(this) { calls++; release.await() })
        repeat(20) { assertFalse(gate.tryLaunch(this) { calls++ }) }
        assertTrue(gate.isClearing.value)
        yield()
        assertEquals(1, calls)
        release.complete(Unit)
        coroutineContext.job.children.toList().joinAll()
        assertFalse(gate.isClearing.value)
        assertTrue(gate.tryLaunch(this) { calls++ })
        coroutineContext.job.children.toList().joinAll()
        assertEquals(2, calls)
    }

    @Test fun refreshIsInsideTheSameBusyWindow() = runBlocking {
        val gate = ManualCacheClear()
        val refresh = CompletableDeferred<Unit>()
        var cleared = false
        gate.tryLaunch(this) { cleared = true; refresh.await() }
        yield()
        assertTrue(cleared)
        assertTrue(gate.isClearing.value)
        assertFalse(gate.tryLaunch(this) { error("overlap") })
        refresh.complete(Unit)
        coroutineContext.job.children.toList().joinAll()
        assertFalse(gate.isClearing.value)
    }

    @Test fun failureReleasesGateForRetry() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> failures += e })
        val gate = ManualCacheClear()
        assertTrue(gate.tryLaunch(scope) { throw IllegalStateException("test failure") })
        assertEquals(1, failures.size)
        assertFalse(gate.isClearing.value)
        assertTrue(gate.tryLaunch(scope) {})
        scope.cancel()
    }

    @Test fun cancelledScopeNeverLeavesBusyState() {
        val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        val gate = ManualCacheClear()
        var ran = false
        assertTrue(gate.tryLaunch(scope) { ran = true })
        assertFalse(ran)
        assertFalse(gate.isClearing.value)
    }

    @Test fun cancellationHoldsGateUntilActionFinallySettles() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settling = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val gate = ManualCacheClear()
        gate.tryLaunch(scope) {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { settling.await() } }
        }
        started.await()
        scope.cancel()
        assertTrue(gate.isClearing.value)
        assertFalse(gate.tryLaunch(this) {})
        settling.complete(Unit)
        scope.coroutineContext.job.join()
        assertFalse(gate.isClearing.value)
        assertTrue(gate.tryLaunch(this) {})
        coroutineContext.job.children.toList().joinAll()
    }
}
