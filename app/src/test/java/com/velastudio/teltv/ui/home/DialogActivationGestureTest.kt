package com.velastudio.teltv.ui.home

import org.junit.Assert.*
import org.junit.Test

class DialogActivationGestureTest {
    @Test fun openingRepeatsAndReleaseCannotClick() {
        val gate = DialogActivationGesture()
        assertTrue(gate.consume(23, true, 2, false))
        assertTrue(gate.consume(23, true, 3, false))
        assertTrue(gate.consume(23, false, 0, false))
    }

    @Test fun orphanReleaseCannotClickButNextCompletePressCan() {
        val gate = DialogActivationGesture()
        assertTrue(gate.consume(23, false, 0, false))
        assertFalse(gate.consume(23, true, 0, false))
        assertFalse(gate.consume(23, false, 0, false))
        assertTrue(gate.consume(23, false, 0, false))
    }

    @Test fun completeHeldPressIsStillOneValidGesture() {
        val gate = DialogActivationGesture()
        assertFalse(gate.consume(66, true, 0, false))
        assertFalse(gate.consume(66, true, 1, false))
        assertFalse(gate.consume(66, true, 2, false))
        assertFalse(gate.consume(66, false, 0, false))
    }

    @Test fun differentActivationKeysDoNotAuthorizeEachOther() {
        val gate = DialogActivationGesture()
        assertFalse(gate.consume(23, true, 0, false))
        assertTrue(gate.consume(66, false, 0, false))
        assertFalse(gate.consume(23, false, 0, false))
    }

    @Test fun cancelledPressCannotClickOrAuthorizeLaterRelease() {
        val gate = DialogActivationGesture()
        assertFalse(gate.consume(23, true, 0, false))
        assertTrue(gate.consume(23, false, 0, true))
        assertTrue(gate.consume(23, false, 0, false))
    }
}
