package dev.j3fftw.worldeditslimefun.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure registry selection rules, independent of MockBukkit version-specific RegistryAccess.
 * The CI bytecode guard separately enforces the supported Material#isAir call.
 */
class MaterialRegistryFilterTest {
    @Test
    void theAirFlagAlwaysExcludesMaterial() {
        assertFalse(Utils.isRegistrableMaterial(true, false));
        assertFalse(Utils.isRegistrableMaterial(true, true));
    }

    @Test
    void modernNonAirIncludedAndLegacyExcluded() {
        assertTrue(Utils.isRegistrableMaterial(false, false));
        assertFalse(Utils.isRegistrableMaterial(false, true));
    }
}
