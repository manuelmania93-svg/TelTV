package com.velastudio.teltv.player

import org.junit.Assert.*
import org.junit.Test

class SeekStepsTest {
    @Test fun choicesAndDefaultStayCompatible() {
        assertEquals(listOf(5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L), SeekSteps.choicesMs)
        assertEquals(10_000L, SeekSteps.normalize(-1))
        assertEquals(10_000L, SeekSteps.normalize(45_000))
        SeekSteps.choicesMs.forEach { assertEquals(it, SeekSteps.normalize(it)) }
    }
    @Test fun labelsMatchMinuteChoices() {
        assertEquals("30 seconds", SeekSteps.label(30_000))
        assertEquals("30s", SeekSteps.badge(30_000))
        assertEquals("1 minute", SeekSteps.label(60_000))
        assertEquals("2 minutes", SeekSteps.label(120_000))
        assertEquals("5 minutes", SeekSteps.label(300_000))
        assertEquals("5 min", SeekSteps.badge(300_000))
    }
    @Test fun nextCyclesThroughEveryChoiceAndWraps() {
        SeekSteps.choicesMs.forEachIndexed { index, choice ->
            assertEquals(SeekSteps.choicesMs[(index + 1) % SeekSteps.choicesMs.size], SeekSteps.next(choice))
        }
        assertEquals(5_000L, SeekSteps.next(300_000L))
    }
    @Test fun nextNormalizesUnknownValuesBeforeCycling() {
        assertEquals(30_000L, SeekSteps.next(45_000L))
        assertEquals(30_000L, SeekSteps.next(-1L))
        assertEquals(30_000L, SeekSteps.next(SeekSteps.DEFAULT_MS))
    }
    @Test fun repeatNeverShrinksLargeSelectedSteps() {
        listOf(30_000L, 60_000L, 120_000L, 300_000L).forEach {
            assertEquals(it, SeekSteps.effectiveStepMs(it, false))
            assertEquals(it, SeekSteps.effectiveStepMs(it, true))
        }
        assertEquals(30_000L, SeekSteps.effectiveStepMs(5_000, true))
        assertEquals(30_000L, SeekSteps.effectiveStepMs(10_000, true))
    }
    @Test fun bothDirectionsUseEverySelectedAmount() {
        SeekSteps.choicesMs.forEach {
            assertEquals(600_000L + it, SeekSteps.targetMs(600_000, 3_600_000, true, it))
            assertEquals(600_000L - it, SeekSteps.targetMs(600_000, 3_600_000, false, it))
        }
    }
    @Test fun endsClampWithoutNegativeOrOverflow() {
        assertEquals(0L, SeekSteps.targetMs(20_000, 100_000, false, 300_000))
        assertEquals(100_000L, SeekSteps.targetMs(90_000, 100_000, true, 300_000))
        assertEquals(Long.MAX_VALUE, SeekSteps.targetMs(Long.MAX_VALUE - 1, -1, true, 300_000))
    }
    @Test fun unknownDurationDoesNotRewindForwardJump() {
        assertEquals(420_000L, SeekSteps.targetMs(120_000, -9223372036854775807L, true, 300_000))
        assertEquals(420_000L, SeekSteps.targetMs(120_000, 0, true, 300_000))
    }
}
