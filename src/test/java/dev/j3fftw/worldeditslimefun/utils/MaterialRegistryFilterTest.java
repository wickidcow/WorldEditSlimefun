package dev.j3fftw.worldeditslimefun.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The supported Material#isAir() must exclude the same three air constants. */
class MaterialRegistryFilterTest {
    @Test
    void airVariantsAreExcludedWithoutUsingDeprecatedMaterialIsEmpty() {
        assertFalse(Utils.isRegistrableMaterial(Material.AIR));
        assertFalse(Utils.isRegistrableMaterial(Material.CAVE_AIR));
        assertFalse(Utils.isRegistrableMaterial(Material.VOID_AIR));
    }

    @Test
    void NonAirModernMaterialsIncludedAndHistoricalLegacyExcluded() {
        assertTrue(Utils.isRegistrableMaterial(Material.STONE));
        assertTrue(Utils.isRegistrableMaterial(Material.WATER));
        // Resolve historical enum by name to avoid using a deprecated legacy constant.
        assertFalse(Utils.isRegistrableMaterial(Material.valueOf("LEGACY_STONE")));
    }
}
