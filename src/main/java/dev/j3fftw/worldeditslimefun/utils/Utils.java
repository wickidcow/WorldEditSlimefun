package dev.j3fftw.worldeditslimefun.utils;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.blocks.UnplaceableBlock;
import io.github.thebusybiscuit.slimefun4.libraries.dough.blocks.BlockPosition;
import org.bukkit.Material;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Utils {

    public static final List<String> SLIMEFUN_BLOCKS = new ArrayList<>();
    public static final List<String> SLIMEFUN_ITEMS = new ArrayList<>();
    public static final Map<String, Material> MATERIALS = new HashMap<>();

    public static void init() {
        for (SlimefunItem item : Slimefun.getRegistry().getEnabledSlimefunItems()) {
            if (!(item instanceof UnplaceableBlock) && item.getItem().getType().isBlock()) {
                SLIMEFUN_BLOCKS.add(item.getId());
            }
            SLIMEFUN_ITEMS.add(item.getId());
        }

        for (Material material : Material.values()) {
            if (isRegistrableMaterial(material)) {
                MATERIALS.put(material.name(), material);
            }
        }

        SLIMEFUN_BLOCKS.sort(Comparator.naturalOrder());
        SLIMEFUN_ITEMS.sort(Comparator.naturalOrder());
    }

    // Material#isEmpty() was deprecated in 1.21.5 in favor of the equivalent isAir().
    static boolean isRegistrableMaterial(Material material) {
        return isRegistrableMaterial(material.isAir(), material.isLegacy());
    }

    // Pure boolean decision boundary permits testing across API vintages;
    // CI also inspects bytecode to ensure the public Material API uses isAir().
    static boolean isRegistrableMaterial(boolean isAir, boolean isLegacy) {
        return !isAir && !isLegacy;
    }

    @Nonnull
    public static String beautifyBlockPosition(@Nonnull BlockPosition position) {
        return "%s, %s, %s (%s)".formatted(position.getX(), position.getY(), position.getZ(), position.getWorld().getName());
    }
}
