package com.velastudio.teltv.ui.home

/** A dialog action requires a fresh press begun inside this dialog, not repeats/release
 * of the long press that opened it. No timers, focus changes or polling are involved. */
internal class DialogActivationGesture {
    private val freshPresses = mutableSetOf<Int>()

    /** True means consume this activation event before a TV button can handle it. */
    fun consume(keyCode: Int, isDown: Boolean, repeatCount: Int, cancelled: Boolean): Boolean {
        if (isDown) {
            if (repeatCount == 0) freshPresses.add(keyCode)
            return keyCode !in freshPresses
        }
        val beganHere = freshPresses.remove(keyCode)
        return cancelled || !beganHere
    }
}
