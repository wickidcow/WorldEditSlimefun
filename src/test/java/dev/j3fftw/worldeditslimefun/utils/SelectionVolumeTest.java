package dev.j3fftw.worldeditslimefun.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.j3fftw.worldeditslimefun.utils.SelectionResolver.Selection;
import org.junit.jupiter.api.Test;

/** Protects synchronous selection limits from integer and long overflow. */
class SelectionVolumeTest {

    @Test
    void smallInclusiveSelectionPreservesHistoricalCount() {
        assertEquals(24L, new Selection(null, -2, -1, -1, 0, 0, 2).volume());
    }

    @Test
    void extremeSingleAxisUsesLongSubtractionBeforeMultiplication() {
        assertEquals(4_294_967_296L,
                new Selection(null, Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0).volume());
    }

    @Test
    void astronomicalSelectionSaturatesInsteadOfWrappingSmallOrNegative() {
        assertEquals(Long.MAX_VALUE, new Selection(
                null,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE).volume());
    }

    @Test
    void invertedSelectionFailsClosedInsteadOfAppearingEmpty() {
        assertEquals(Long.MAX_VALUE, new Selection(null, 5, 0, 0, 3, 0, 0).volume());
    }
}
