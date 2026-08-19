package dev.j3fftw.worldeditslimefun.utils;

import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import dev.j3fftw.worldeditslimefun.WorldEditSlimefun;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.blocks.UnplaceableBlock;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort recovery for old FAWE/WorldEdit schematics which predate WESF sidecars.
 *
 * <p>Sponge schematics can preserve PDC on tile entities, but ordinary blocks such as
 * stained glass and glazed terracotta have nowhere to store a Slimefun block id. This
 * pass runs after the exact embedded-PDC relink and infers a limited set of well-known
 * addon blocks from their physical material. Exact PDC/BlockStorage data always wins.</p>
 */
@SuppressWarnings("deprecation")
public final class LegacySchematicInference {

    private static final Map<Material, String[]> NETWORK_IDS = new EnumMap<>(Material.class);
    private static final Map<Material, String[]> FLUFFY_BARREL_IDS = new EnumMap<>(Material.class);
    private static final Set<Material> NETWORK_MATERIALS = new HashSet<>();
    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    static {
        // Networks - direct material identities from the addon. Two materials are
        // intentionally ambiguous and are reported instead of guessed.
        network(Material.BLACK_STAINED_GLASS, "NTW_CONTROLLER");
        network(Material.WHITE_STAINED_GLASS, "NTW_BRIDGE");
        network(Material.GREEN_STAINED_GLASS, "NTW_MONITOR");
        network(Material.RED_STAINED_GLASS, "NTW_IMPORT");
        network(Material.BLUE_STAINED_GLASS, "NTW_EXPORT");
        network(Material.MAGENTA_STAINED_GLASS, "NTW_GRABBER");
        network(Material.BROWN_STAINED_GLASS, "NTW_PUSHER");
        network(Material.WHITE_GLAZED_TERRACOTTA, "NTW_CONTROL_X", "NTW_AUTO_CRAFTER_WITHHOLDING");
        network(Material.PURPLE_GLAZED_TERRACOTTA, "NTW_CONTROL_V");
        network(Material.ORANGE_GLAZED_TERRACOTTA, "NTW_VACUUM");
        network(Material.ORANGE_STAINED_GLASS, "NTW_VANILLA_GRABBER");
        network(Material.LIME_STAINED_GLASS, "NTW_VANILLA_PUSHER");
        network(Material.CYAN_STAINED_GLASS, "NTW_NETWORK_WIRELESS_TRANSMITTER");
        network(Material.PURPLE_STAINED_GLASS, "NTW_NETWORK_WIRELESS_RECEIVER");
        network(Material.OBSERVER, "NTW_TRASH");
        network(Material.NOTE_BLOCK, "NTW_GRID");
        network(Material.REDSTONE_LAMP, "NTW_CRAFTING_GRID");
        network(Material.HONEYCOMB_BLOCK, "NTW_CELL");
        network(Material.SHROOMLIGHT, "NTW_GREEDY_BLOCK");
        network(Material.DRIED_KELP_BLOCK, "NTW_QUANTUM_WORKBENCH");
        network(Material.WHITE_TERRACOTTA, "NTW_QUANTUM_STORAGE_1");
        network(Material.LIGHT_GRAY_TERRACOTTA, "NTW_QUANTUM_STORAGE_2");
        network(Material.GRAY_TERRACOTTA, "NTW_QUANTUM_STORAGE_3");
        network(Material.BROWN_TERRACOTTA, "NTW_QUANTUM_STORAGE_4");
        network(Material.BLACK_TERRACOTTA, "NTW_QUANTUM_STORAGE_5");
        network(Material.PURPLE_TERRACOTTA, "NTW_QUANTUM_STORAGE_6");
        network(Material.MAGENTA_TERRACOTTA, "NTW_QUANTUM_STORAGE_7");
        network(Material.RED_TERRACOTTA, "NTW_QUANTUM_STORAGE_8");
        network(Material.BROWN_GLAZED_TERRACOTTA, "NTW_CAPACITOR_1");
        network(Material.GREEN_GLAZED_TERRACOTTA, "NTW_CAPACITOR_2");
        network(Material.BLACK_GLAZED_TERRACOTTA, "NTW_CAPACITOR_3", "NTW_AUTO_CRAFTER");
        network(Material.GRAY_GLAZED_TERRACOTTA, "NTW_CAPACITOR_4");
        network(Material.YELLOW_GLAZED_TERRACOTTA, "NTW_POWER_OUTLET_1");
        network(Material.RED_GLAZED_TERRACOTTA, "NTW_POWER_OUTLET_2");
        network(Material.TINTED_GLASS, "NTW_POWER_DISPLAY");
        network(Material.TARGET, "NTW_RECIPE_ENCODER");

        // FluffyMachines barrel tiers. Their contents live in Slimefun storage,
        // so an old .schem can recreate the working barrel but not missing counts/items.
        fluffy(Material.BEEHIVE, "SMALL_FLUFFY_BARREL");
        fluffy(Material.BARREL, "MEDIUM_FLUFFY_BARREL");
        fluffy(Material.SMOKER, "BIG_FLUFFY_BARREL");
        fluffy(Material.LODESTONE, "LARGE_FLUFFY_BARREL");
        fluffy(Material.CRYING_OBSIDIAN, "MASSIVE_FLUFFY_BARREL");
        fluffy(Material.RESPAWN_ANCHOR, "BOTTOMLESS_FLUFFY_BARREL");
    }

    private LegacySchematicInference() {}

    public static void restore(@Nonnull Player player) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        LocalSession session = worldEdit.getSessionManager().get(BukkitAdapter.adapt(player));

        final ClipboardHolder holder;
        final BlockVector3 destination;
        try {
            holder = session.getClipboard();
            destination = session.getPlacementPosition(BukkitAdapter.adapt(player));
        } catch (EmptyClipboardException | IncompleteRegionException ex) {
            return;
        }

        Clipboard clipboard = holder.getClipboard();
        long inferred = 0;
        long betterChestsRefreshed = 0;
        long ambiguous = 0;
        long unavailable = 0;
        long failed = 0;

        for (BlockVector3 source : clipboard.getRegion()) {
            BlockVector3 relative = source.subtract(clipboard.getOrigin());
            BlockVector3 target = transformRelative(holder, destination, relative);
            Block block = player.getWorld().getBlockAt(target.x(), target.y(), target.z());

            String existing = BlockStorage.checkID(block.getLocation());
            if (existing != null) {
                if (existing.startsWith("BC_DRAWER_")) {
                    if (refreshBetterChest(player, block, existing)) {
                        betterChestsRefreshed++;
                    }
                }
                continue;
            }

            Inference inference = infer(block);
            if (inference == null) {
                continue;
            }
            if (inference.ambiguous()) {
                ambiguous++;
                continue;
            }

            SlimefunItem item = firstRegistered(inference.ids());
            if (item == null || item instanceof UnplaceableBlock) {
                unavailable++;
                continue;
            }

            try {
                BlockStorage.store(block, item.getId());
                callPlaceHandler(player, block, item);
                inferred++;
            } catch (Exception | LinkageError ex) {
                failed++;
                WorldEditSlimefun.getInstance().getLogger().warning(
                        "Legacy schematic inference failed for " + item.getId() + " at "
                                + block.getX() + ',' + block.getY() + ',' + block.getZ() + ": " + ex.getMessage());
            }
        }

        if (inferred == 0 && betterChestsRefreshed == 0 && ambiguous == 0 && unavailable == 0 && failed == 0) {
            return;
        }

        player.sendMessage(ChatColor.AQUA + "Legacy addon recovery:");
        player.sendMessage(ChatColor.GRAY + "Material-inferred Slimefun blocks: " + ChatColor.WHITE + inferred);
        if (betterChestsRefreshed > 0) {
            player.sendMessage(ChatColor.GRAY + "BetterChests drawers refreshed: " + ChatColor.WHITE + betterChestsRefreshed);
        }
        if (ambiguous > 0) {
            player.sendMessage(ChatColor.YELLOW + "Skipped " + ambiguous
                    + " ambiguous Networks blocks rather than guessing the wrong machine type.");
        }
        if (unavailable > 0) {
            player.sendMessage(ChatColor.YELLOW + "Skipped " + unavailable
                    + " inferred blocks because their addon/item id is not registered on this server.");
        }
        if (failed > 0) {
            player.sendMessage(ChatColor.RED + "Failed to initialize " + failed + " inferred blocks; check console.");
        }
        player.sendMessage(ChatColor.GRAY + "Old schematics cannot recreate addon data that existed only in an external database.");
    }

    @Nullable
    private static Inference infer(Block block) {
        // InfinityExpansion 2 renamed the old INFINITE_PANEL id to INFINITY_PANEL.
        // Prefer the current id and retain the original as a compatibility fallback.
        if (block.getType() == Material.LIGHT_BLUE_GLAZED_TERRACOTTA) {
            return new Inference(new String[]{"INFINITY_PANEL", "INFINITE_PANEL"}, false);
        }

        String[] networkIds = NETWORK_IDS.get(block.getType());
        if (networkIds != null && looksConnectedToNetwork(block)) {
            return new Inference(networkIds, networkIds.length > 1);
        }

        String[] fluffyIds = FLUFFY_BARREL_IDS.get(block.getType());
        if (fluffyIds != null && isSafeFluffyCandidate(block)) {
            return new Inference(fluffyIds, false);
        }

        return null;
    }

    private static boolean looksConnectedToNetwork(Block block) {
        for (BlockFace face : FACES) {
            Block relative = block.getRelative(face);
            String neighborId = BlockStorage.checkID(relative.getLocation());
            if (neighborId != null && neighborId.startsWith("NTW_")) {
                return true;
            }
            if (NETWORK_MATERIALS.contains(relative.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeFluffyCandidate(Block block) {
        // Medium barrels and smokers can be genuine vanilla containers. Do not
        // overwrite a non-empty vanilla inventory during a recovery guess.
        if (block.getState() instanceof Container container) {
            return isInventoryEmpty(container.getInventory());
        }
        return true;
    }

    private static boolean isInventoryEmpty(Inventory inventory) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean refreshBetterChest(Player player, Block block, String id) {
        SlimefunItem item = SlimefunItem.getById(id);
        if (item == null) {
            return false;
        }

        try {
            callPlaceHandler(player, block, item);
            Bukkit.getScheduler().runTask(WorldEditSlimefun.getInstance(), () -> repairBetterChestDisplay(block));
            return true;
        } catch (RuntimeException | LinkageError ex) {
            WorldEditSlimefun.getInstance().getLogger().warning(
                    "Could not refresh BetterChests drawer " + id + " at " + block.getLocation() + ": " + ex.getMessage());
            return false;
        }
    }

    private static void repairBetterChestDisplay(Block block) {
        try {
            Class<?> manager = Class.forName("me.mmmjjkx.betterChests.storage.DrawerDisplayManager");
            Method repair = manager.getMethod("repair", Block.class);
            repair.invoke(null, block);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // BetterChests may not be the modern fork or may not expose this optional repair hook.
        }
    }

    private static void callPlaceHandler(Player player, Block block, SlimefunItem item) {
        ItemStack stack = item.getItem();
        item.callItemHandler(BlockPlaceHandler.class, handler -> handler.onPlayerPlace(new BlockPlaceEvent(
                block,
                block.getState(),
                block.getRelative(BlockFace.DOWN),
                stack,
                player,
                true,
                EquipmentSlot.HAND)));
    }

    @Nullable
    private static SlimefunItem firstRegistered(String[] ids) {
        for (String id : ids) {
            SlimefunItem item = SlimefunItem.getById(id);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private static BlockVector3 transformRelative(ClipboardHolder holder, BlockVector3 destination, BlockVector3 relative) {
        Vector3 transformed = holder.getTransform().apply(relative.toVector3());
        return destination.toVector3().add(transformed).toBlockPoint();
    }

    private static void network(Material material, String... ids) {
        NETWORK_IDS.put(material, ids);
        NETWORK_MATERIALS.add(material);
    }

    private static void fluffy(Material material, String... ids) {
        FLUFFY_BARREL_IDS.put(material, ids);
    }

    private record Inference(String[] ids, boolean ambiguous) {}
}
