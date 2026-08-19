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
import com.sk89q.worldedit.world.block.BaseBlock;
import dev.j3fftw.worldeditslimefun.WorldEditSlimefun;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.blocks.UnplaceableBlock;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Best-effort recovery for old FAWE/WorldEdit schematics which predate WESF sidecars.
 *
 * <p>Old Sponge schematics preserve block-entity NBT, but ordinary blocks such as
 * stained glass, terracotta and logs have nowhere to store a Slimefun block id. This
 * recovery pass therefore works in confidence layers: exact clipboard NBT first,
 * known addon material mappings second, and finally a conservative runtime-registry
 * inference for rare, uniquely-identifiable block materials near already-restored
 * Slimefun machinery. Exact PDC/BlockStorage data always wins.</p>
 */
@SuppressWarnings({"deprecation", "unchecked"})
public final class LegacySchematicInference {

    private static final int GENERIC_MAX_OCCURRENCES = 24;
    private static final int GENERIC_NEIGHBOR_RADIUS = 2;
    private static final int GENERIC_PASSES = 3;
    private static final int REPORT_SAMPLE_LIMIT = 5;

    private static final Map<Material, String[]> NETWORK_IDS = new EnumMap<>(Material.class);
    private static final Map<Material, String[]> FLUFFY_BARREL_IDS = new EnumMap<>(Material.class);
    private static final Set<Material> NETWORK_MATERIALS = EnumSet.noneOf(Material.class);
    private static final Set<Material> STRONG_NETWORK_MATERIALS = EnumSet.noneOf(Material.class);
    private static final Set<Material> RISKY_GENERIC_MATERIALS = EnumSet.of(
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.BARREL,
            Material.SMOKER,
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.HOPPER,
            Material.CRAFTING_TABLE,
            Material.DISPENSER,
            Material.DROPPER,
            Material.BEEHIVE,
            Material.BEE_NEST
    );

    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    static {
        // Networks. Materials which identify two current Network items remain
        // intentionally ambiguous unless only one of those IDs is registered.
        network(Material.BLACK_STAINED_GLASS, false, "NTW_CONTROLLER");
        network(Material.WHITE_STAINED_GLASS, false, "NTW_BRIDGE");
        network(Material.GREEN_STAINED_GLASS, false, "NTW_MONITOR");
        network(Material.RED_STAINED_GLASS, false, "NTW_IMPORT");
        network(Material.BLUE_STAINED_GLASS, false, "NTW_EXPORT");
        network(Material.MAGENTA_STAINED_GLASS, false, "NTW_GRABBER");
        network(Material.BROWN_STAINED_GLASS, false, "NTW_PUSHER");
        network(Material.WHITE_GLAZED_TERRACOTTA, true, "NTW_CONTROL_X", "NTW_AUTO_CRAFTER_WITHHOLDING");
        network(Material.PURPLE_GLAZED_TERRACOTTA, true, "NTW_CONTROL_V");
        network(Material.ORANGE_GLAZED_TERRACOTTA, true, "NTW_VACUUM");
        network(Material.ORANGE_STAINED_GLASS, false, "NTW_VANILLA_GRABBER");
        network(Material.LIME_STAINED_GLASS, false, "NTW_VANILLA_PUSHER");
        network(Material.CYAN_STAINED_GLASS, false, "NTW_NETWORK_WIRELESS_TRANSMITTER");
        network(Material.PURPLE_STAINED_GLASS, false, "NTW_NETWORK_WIRELESS_RECEIVER");
        network(Material.OBSERVER, true, "NTW_TRASH");
        network(Material.NOTE_BLOCK, true, "NTW_GRID");
        network(Material.REDSTONE_LAMP, true, "NTW_CRAFTING_GRID");
        network(Material.HONEYCOMB_BLOCK, true, "NTW_CELL");
        network(Material.SHROOMLIGHT, true, "NTW_GREEDY_BLOCK");
        network(Material.DRIED_KELP_BLOCK, true, "NTW_QUANTUM_WORKBENCH");
        network(Material.WHITE_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_1");
        network(Material.LIGHT_GRAY_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_2");
        network(Material.GRAY_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_3");
        network(Material.BROWN_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_4");
        network(Material.BLACK_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_5");
        network(Material.PURPLE_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_6");
        network(Material.MAGENTA_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_7");
        network(Material.RED_TERRACOTTA, true, "NTW_QUANTUM_STORAGE_8");
        network(Material.BROWN_GLAZED_TERRACOTTA, true, "NTW_CAPACITOR_1");
        network(Material.GREEN_GLAZED_TERRACOTTA, true, "NTW_CAPACITOR_2");
        network(Material.BLACK_GLAZED_TERRACOTTA, true, "NTW_CAPACITOR_3", "NTW_AUTO_CRAFTER");
        network(Material.GRAY_GLAZED_TERRACOTTA, true, "NTW_CAPACITOR_4");
        network(Material.YELLOW_GLAZED_TERRACOTTA, true, "NTW_POWER_OUTLET_1");
        network(Material.RED_GLAZED_TERRACOTTA, true, "NTW_POWER_OUTLET_2");
        network(Material.TINTED_GLASS, true, "NTW_POWER_DISPLAY");
        network(Material.TARGET, true, "NTW_RECIPE_ENCODER");

        // FluffyMachines barrel tiers. Low tiers use ordinary containers and are
        // only guessed when another restored Fluffy Barrel is close by. Distinctive
        // high-tier materials can be recovered with a much higher confidence.
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
        Map<Material, List<SlimefunItem>> registryByMaterial = buildRegistryIndex();
        Map<Material, Integer> materialCounts = new EnumMap<>(Material.class);
        Map<Material, List<CandidateBlock>> genericBuckets = new EnumMap<>(Material.class);
        Map<Material, List<String>> genericSamples = new EnumMap<>(Material.class);
        Set<Material> genericOverflow = EnumSet.noneOf(Material.class);
        Map<BlockVector3, CandidateBlock> networkPhysical = new HashMap<>();
        List<CandidateBlock> specialCandidates = new ArrayList<>();
        Set<BlockVector3> knownSlimefun = new HashSet<>();
        List<String> exactUnresolved = new ArrayList<>();
        Stats stats = new Stats();

        // One full scan seeds exact identities, captures material frequencies and
        // collects only bounded candidate sets for later confidence passes.
        for (BlockVector3 source : clipboard.getRegion()) {
            BlockVector3 relative = source.subtract(clipboard.getOrigin());
            BlockVector3 target = transformRelative(holder, destination, relative);
            Block block = player.getWorld().getBlockAt(target.x(), target.y(), target.z());
            Material material = block.getType();
            CandidateBlock candidate = new CandidateBlock(source, target, material);

            materialCounts.merge(material, 1, Integer::sum);
            if (NETWORK_IDS.containsKey(material)) {
                networkPhysical.put(target, candidate);
            }

            String existing = BlockStorage.checkID(block.getLocation());
            if (existing != null) {
                knownSlimefun.add(target);
                if (existing.startsWith("BC_DRAWER_") && refreshBetterChest(player, block, existing)) {
                    stats.betterChestsRefreshed++;
                }
                continue;
            }

            String exactId = findClipboardSlimefunId(clipboard.getFullBlock(source));
            if (exactId != null) {
                SlimefunItem exactItem = SlimefunItem.getById(exactId);
                if (!isRecoverableBlock(exactItem)) {
                    stats.exactUnavailable++;
                    exactUnresolved.add(location(target) + " -> " + exactId + " (not registered/placeable)");
                    continue;
                }
                if (exactItem.getItem().getType() != material) {
                    stats.exactMaterialMismatch++;
                    exactUnresolved.add(location(target) + " -> " + exactId + " (schematic " + material
                            + ", current " + exactItem.getItem().getType() + ')');
                    continue;
                }
                if (restoreItem(player, block, exactItem)) {
                    stats.exactNbtRestored++;
                    knownSlimefun.add(target);
                } else {
                    stats.failed++;
                    exactUnresolved.add(location(target) + " -> " + exactId + " (initialization failed)");
                }
                continue;
            }

            if (material == Material.LIGHT_BLUE_GLAZED_TERRACOTTA || FLUFFY_BARREL_IDS.containsKey(material)) {
                specialCandidates.add(candidate);
            }

            if (registryByMaterial.containsKey(material)) {
                rememberGenericCandidate(genericBuckets, genericSamples, genericOverflow, candidate);
            }
        }

        restoreInfinityPanel(player, specialCandidates, knownSlimefun, stats);
        restoreNetworks(player, networkPhysical, knownSlimefun, stats);
        restoreFluffyBarrels(player, specialCandidates, materialCounts, knownSlimefun, stats);
        restoreGenericUniqueMaterials(player, genericBuckets, genericOverflow, registryByMaterial,
                materialCounts, knownSlimefun, stats);

        File report = writeReport(player, materialCounts, registryByMaterial, genericSamples,
                genericOverflow, exactUnresolved, stats);

        if (stats.hasOutput()) {
            player.sendMessage(ChatColor.AQUA + "Legacy addon recovery 1.0.4:");
            player.sendMessage(ChatColor.GRAY + "Exact IDs recovered from schematic NBT: "
                    + ChatColor.WHITE + stats.exactNbtRestored);
            player.sendMessage(ChatColor.GRAY + "Known addon blocks inferred: "
                    + ChatColor.WHITE + stats.knownInferred);
            player.sendMessage(ChatColor.GRAY + "Unique registry matches inferred: "
                    + ChatColor.WHITE + stats.genericInferred);
            if (stats.betterChestsRefreshed > 0) {
                player.sendMessage(ChatColor.GRAY + "BetterChests drawers refreshed: "
                        + ChatColor.WHITE + stats.betterChestsRefreshed);
            }
            if (stats.ambiguous > 0) {
                player.sendMessage(ChatColor.YELLOW + "Skipped " + stats.ambiguous
                        + " ambiguous blocks rather than guessing the wrong machine type.");
            }
            if (stats.exactUnavailable + stats.unavailable + stats.exactMaterialMismatch > 0) {
                player.sendMessage(ChatColor.YELLOW + "Some recoverable-looking blocks were left unresolved; see the report.");
            }
            if (stats.failed > 0) {
                player.sendMessage(ChatColor.RED + "Failed to initialize " + stats.failed + " blocks; check console/report.");
            }
        }

        if (report != null) {
            player.sendMessage(ChatColor.GRAY + "Recovery report: " + ChatColor.WHITE
                    + "plugins/WorldEditSlimefun/recovery-reports/" + report.getName());
        }
        player.sendMessage(ChatColor.GRAY
                + "Inventory/count data that existed only in an old external addon database cannot be recreated from the .schem alone.");
    }

    private static void restoreInfinityPanel(Player player, List<CandidateBlock> candidates,
                                             Set<BlockVector3> known, Stats stats) {
        for (CandidateBlock candidate : candidates) {
            if (candidate.material() != Material.LIGHT_BLUE_GLAZED_TERRACOTTA) {
                continue;
            }
            Block block = block(player, candidate.target());
            if (BlockStorage.checkID(block.getLocation()) != null) {
                continue;
            }

            SlimefunItem item = firstRegistered("INFINITY_PANEL", "INFINITE_PANEL");
            if (!isRecoverableBlock(item)) {
                stats.unavailable++;
                continue;
            }
            if (restoreItem(player, block, item)) {
                stats.knownInferred++;
                known.add(candidate.target());
            } else {
                stats.failed++;
            }
        }
    }

    private static void restoreNetworks(Player player, Map<BlockVector3, CandidateBlock> physical,
                                        Set<BlockVector3> known, Stats stats) {
        Set<BlockVector3> unvisited = new HashSet<>(physical.keySet());
        while (!unvisited.isEmpty()) {
            BlockVector3 seed = unvisited.iterator().next();
            ArrayDeque<BlockVector3> queue = new ArrayDeque<>();
            List<CandidateBlock> component = new ArrayList<>();
            Set<Material> distinct = EnumSet.noneOf(Material.class);
            boolean hasStrongMaterial = false;
            boolean hasController = false;
            boolean hasBridge = false;
            queue.add(seed);
            unvisited.remove(seed);

            while (!queue.isEmpty()) {
                BlockVector3 position = queue.removeFirst();
                CandidateBlock candidate = physical.get(position);
                if (candidate == null) {
                    continue;
                }
                component.add(candidate);
                distinct.add(candidate.material());
                hasStrongMaterial |= STRONG_NETWORK_MATERIALS.contains(candidate.material());
                hasController |= candidate.material() == Material.BLACK_STAINED_GLASS;
                hasBridge |= candidate.material() == Material.WHITE_STAINED_GLASS;

                for (BlockFace face : FACES) {
                    BlockVector3 neighbor = position.add(face.getModX(), face.getModY(), face.getModZ());
                    if (physical.containsKey(neighbor) && unvisited.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            boolean confident = component.size() >= 2
                    && (distinct.size() >= 2 || (hasStrongMaterial && component.size() >= 3) || (hasController && hasBridge));
            if (!confident) {
                continue;
            }

            for (CandidateBlock candidate : component) {
                Block block = block(player, candidate.target());
                String existing = BlockStorage.checkID(block.getLocation());
                if (existing != null) {
                    known.add(candidate.target());
                    continue;
                }

                List<SlimefunItem> registered = registered(NETWORK_IDS.get(candidate.material()));
                if (registered.isEmpty()) {
                    stats.unavailable++;
                    continue;
                }
                if (registered.size() != 1) {
                    stats.ambiguous++;
                    continue;
                }

                SlimefunItem item = registered.get(0);
                if (!isRecoverableBlock(item) || item.getItem().getType() != candidate.material()) {
                    stats.unavailable++;
                    continue;
                }
                if (restoreItem(player, block, item)) {
                    stats.knownInferred++;
                    known.add(candidate.target());
                } else {
                    stats.failed++;
                }
            }
        }
    }

    private static void restoreFluffyBarrels(Player player, List<CandidateBlock> candidates,
                                             Map<Material, Integer> materialCounts,
                                             Set<BlockVector3> known, Stats stats) {
        for (CandidateBlock candidate : candidates) {
            String[] ids = FLUFFY_BARREL_IDS.get(candidate.material());
            if (ids == null) {
                continue;
            }

            Block block = block(player, candidate.target());
            if (BlockStorage.checkID(block.getLocation()) != null || !isSafeContainerCandidate(block)) {
                continue;
            }

            boolean distinctive = candidate.material() == Material.LODESTONE
                    || candidate.material() == Material.CRYING_OBSIDIAN
                    || candidate.material() == Material.RESPAWN_ANCHOR;
            boolean nearbyFluffy = hasNearbyId(player, candidate.target(), 2,
                    id -> id.endsWith("_FLUFFY_BARREL"));
            int occurrences = materialCounts.getOrDefault(candidate.material(), 0);

            // This is intentionally stricter than 1.0.3. A BEEHIVE can be IE2's
            // STORAGE_FORGE (and the supplied legacy schematic contains exactly that
            // case), while BARREL/SMOKER are common vanilla containers.
            if (!(nearbyFluffy || (distinctive && (occurrences <= 8 || hasKnownWithin(candidate.target(), known, 4))))) {
                continue;
            }

            SlimefunItem item = firstRegistered(ids);
            if (!isRecoverableBlock(item)) {
                stats.unavailable++;
                continue;
            }
            if (restoreItem(player, block, item)) {
                stats.knownInferred++;
                known.add(candidate.target());
            } else {
                stats.failed++;
            }
        }
    }

    private static void restoreGenericUniqueMaterials(Player player,
                                                      Map<Material, List<CandidateBlock>> buckets,
                                                      Set<Material> overflow,
                                                      Map<Material, List<SlimefunItem>> registry,
                                                      Map<Material, Integer> materialCounts,
                                                      Set<BlockVector3> known,
                                                      Stats stats) {
        for (int pass = 0; pass < GENERIC_PASSES; pass++) {
            long before = stats.genericInferred;
            for (Map.Entry<Material, List<CandidateBlock>> entry : buckets.entrySet()) {
                Material material = entry.getKey();
                if (overflow.contains(material)
                        || NETWORK_IDS.containsKey(material)
                        || FLUFFY_BARREL_IDS.containsKey(material)
                        || material == Material.LIGHT_BLUE_GLAZED_TERRACOTTA) {
                    continue;
                }

                List<SlimefunItem> matches = registry.get(material);
                if (matches == null || matches.size() != 1) {
                    continue;
                }
                SlimefunItem item = matches.get(0);
                if (!isRecoverableBlock(item)) {
                    continue;
                }

                int occurrences = materialCounts.getOrDefault(material, 0);
                if (occurrences > GENERIC_MAX_OCCURRENCES) {
                    continue;
                }
                if (RISKY_GENERIC_MATERIALS.contains(material) && occurrences > 4) {
                    continue;
                }

                for (CandidateBlock candidate : entry.getValue()) {
                    Block block = block(player, candidate.target());
                    if (BlockStorage.checkID(block.getLocation()) != null) {
                        continue;
                    }
                    if (!hasKnownWithin(candidate.target(), known, GENERIC_NEIGHBOR_RADIUS)
                            || !isSafeContainerCandidate(block)) {
                        continue;
                    }
                    if (item.getItem().getType() != material) {
                        continue;
                    }

                    if (restoreItem(player, block, item)) {
                        stats.genericInferred++;
                        known.add(candidate.target());
                    } else {
                        stats.failed++;
                    }
                }
            }

            if (stats.genericInferred == before) {
                break;
            }
        }
    }

    private static Map<Material, List<SlimefunItem>> buildRegistryIndex() {
        Map<Material, List<SlimefunItem>> result = new EnumMap<>(Material.class);
        for (SlimefunItem item : Slimefun.getRegistry().getEnabledSlimefunItems()) {
            if (!isRecoverableBlock(item)) {
                continue;
            }
            result.computeIfAbsent(item.getItem().getType(), ignored -> new ArrayList<>()).add(item);
        }
        result.values().forEach(items -> items.sort(Comparator.comparing(SlimefunItem::getId)));
        return result;
    }

    private static void rememberGenericCandidate(Map<Material, List<CandidateBlock>> buckets,
                                                 Map<Material, List<String>> samples,
                                                 Set<Material> overflow,
                                                 CandidateBlock candidate) {
        List<String> materialSamples = samples.computeIfAbsent(candidate.material(), ignored -> new ArrayList<>());
        if (materialSamples.size() < REPORT_SAMPLE_LIMIT) {
            materialSamples.add(location(candidate.target()));
        }

        if (overflow.contains(candidate.material())) {
            return;
        }
        List<CandidateBlock> list = buckets.computeIfAbsent(candidate.material(), ignored -> new ArrayList<>());
        list.add(candidate);
        if (list.size() > GENERIC_MAX_OCCURRENCES) {
            list.clear();
            overflow.add(candidate.material());
        }
    }

    @Nullable
    private static String findClipboardSlimefunId(BaseBlock sourceBlock) {
        if (sourceBlock.getNbtReference() == null) {
            return null;
        }
        LinCompoundTag nbt = sourceBlock.getNbtReference().getValue();
        String blockId = findStringRecursive(nbt, "slimefun:slimefun_block");
        if (blockId != null && !blockId.isBlank()) {
            return blockId;
        }
        return findStringRecursive(nbt, "slimefun:slimefun_item");
    }

    @Nullable
    private static String findStringRecursive(LinCompoundTag compound, String key) {
        LinTag<?> direct = compound.value().get(key);
        if (direct instanceof LinStringTag stringTag) {
            return stringTag.value();
        }
        for (LinTag<?> child : compound.value().values()) {
            if (child instanceof LinCompoundTag nested) {
                String found = findStringRecursive(nested, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean restoreItem(Player player, Block block, SlimefunItem item) {
        if (BlockStorage.checkID(block.getLocation()) != null) {
            return false;
        }

        try {
            try {
                BlockStorage.store(block, item.getId());
                callPlaceHandler(player, block, item);
            } catch (IllegalArgumentException normalStorageFailure) {
                if (!createUniversalBlock(block.getLocation(), item.getId())) {
                    throw normalStorageFailure;
                }
                callPlaceHandlerSafely(player, block, item);
            }
            return true;
        } catch (Exception | LinkageError ex) {
            WorldEditSlimefun.getInstance().getLogger().warning(
                    "Legacy schematic recovery failed for " + item.getId() + " at "
                            + block.getX() + ',' + block.getY() + ',' + block.getZ() + ": " + rootMessage(ex));
            return false;
        }
    }

    private static boolean createUniversalBlock(Location location, String id) throws ReflectiveOperationException {
        try {
            Class<?> slimefun = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
            Object database = slimefun.getMethod("getDatabaseManager").invoke(null);
            if (database == null) {
                return false;
            }
            Object controller = database.getClass().getMethod("getBlockDataController").invoke(database);
            if (controller == null) {
                return false;
            }
            Object created = controller.getClass()
                    .getMethod("createUniversalBlock", Location.class, String.class)
                    .invoke(controller, location, id);
            return created != null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ReflectiveOperationException reflection) {
                throw reflection;
            }
            throw ex;
        }
    }

    private static boolean isRecoverableBlock(@Nullable SlimefunItem item) {
        return item != null
                && !(item instanceof UnplaceableBlock)
                && item.getItem().getType().isBlock();
    }

    private static boolean isSafeContainerCandidate(Block block) {
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

    private static boolean hasKnownWithin(BlockVector3 center, Set<BlockVector3> known, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    if (known.contains(center.add(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyId(Player player, BlockVector3 center, int radius, Predicate<String> predicate) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    String id = BlockStorage.checkID(player.getWorld()
                            .getBlockAt(center.x() + x, center.y() + y, center.z() + z).getLocation());
                    if (id != null && predicate.test(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static void callPlaceHandlerSafely(Player player, Block block, SlimefunItem item) {
        try {
            callPlaceHandler(player, block, item);
        } catch (RuntimeException | LinkageError ex) {
            WorldEditSlimefun.getInstance().getLogger().warning(
                    "Placement hook failed for " + item.getId() + " at " + block.getLocation() + ": " + ex.getMessage());
        }
    }

    private static List<SlimefunItem> registered(@Nullable String[] ids) {
        if (ids == null) {
            return List.of();
        }
        List<SlimefunItem> result = new ArrayList<>();
        for (String id : ids) {
            SlimefunItem item = SlimefunItem.getById(id);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    @Nullable
    private static SlimefunItem firstRegistered(String... ids) {
        for (String id : ids) {
            SlimefunItem item = SlimefunItem.getById(id);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    @Nullable
    private static File writeReport(Player player,
                                    Map<Material, Integer> materialCounts,
                                    Map<Material, List<SlimefunItem>> registry,
                                    Map<Material, List<String>> samples,
                                    Set<Material> overflow,
                                    List<String> exactUnresolved,
                                    Stats stats) {
        try {
            File directory = new File(WorldEditSlimefun.getInstance().getDataFolder(), "recovery-reports");
            if (!directory.exists() && !directory.mkdirs()) {
                return null;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File file = new File(directory, "legacy-recovery-" + timestamp + ".yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("version", 2);
            yaml.set("world", player.getWorld().getName());
            yaml.set("player", player.getName());
            yaml.set("summary.exact_nbt_restored", stats.exactNbtRestored);
            yaml.set("summary.known_addon_inferred", stats.knownInferred);
            yaml.set("summary.generic_unique_inferred", stats.genericInferred);
            yaml.set("summary.betterchests_refreshed", stats.betterChestsRefreshed);
            yaml.set("summary.ambiguous_skipped", stats.ambiguous);
            yaml.set("summary.unavailable", stats.unavailable);
            yaml.set("summary.exact_unavailable", stats.exactUnavailable);
            yaml.set("summary.exact_material_mismatch", stats.exactMaterialMismatch);
            yaml.set("summary.failed", stats.failed);
            yaml.set("exact_unresolved", exactUnresolved);

            List<Map<String, Object>> candidateMaterials = new ArrayList<>();
            materialCounts.entrySet().stream()
                    .filter(entry -> registry.containsKey(entry.getKey()))
                    .sorted(Comparator.comparingInt(Map.Entry<Material, Integer>::getValue)
                            .thenComparing(entry -> entry.getKey().name()))
                    .limit(250)
                    .forEach(entry -> {
                        Material material = entry.getKey();
                        List<SlimefunItem> matches = registry.get(material);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("material", material.name());
                        row.put("schematic_count", entry.getValue());
                        row.put("registered_ids", matches.stream().map(SlimefunItem::getId).toList());
                        row.put("unique_registered_id", matches.size() == 1);
                        row.put("too_common_for_auto_inference", overflow.contains(material)
                                || entry.getValue() > GENERIC_MAX_OCCURRENCES);
                        row.put("samples", samples.getOrDefault(material, List.of()));
                        candidateMaterials.add(row);
                    });
            yaml.set("material_candidates", candidateMaterials);
            yaml.save(file);
            return file;
        } catch (IOException | RuntimeException ex) {
            WorldEditSlimefun.getInstance().getLogger().warning("Could not write recovery report: " + ex.getMessage());
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Block block(Player player, BlockVector3 position) {
        return player.getWorld().getBlockAt(position.x(), position.y(), position.z());
    }

    private static String location(BlockVector3 position) {
        return position.x() + "," + position.y() + "," + position.z();
    }

    private static BlockVector3 transformRelative(ClipboardHolder holder, BlockVector3 destination, BlockVector3 relative) {
        Vector3 transformed = holder.getTransform().apply(relative.toVector3());
        return destination.toVector3().add(transformed).toBlockPoint();
    }

    private static void network(Material material, boolean strong, String... ids) {
        NETWORK_IDS.put(material, ids);
        NETWORK_MATERIALS.add(material);
        if (strong) {
            STRONG_NETWORK_MATERIALS.add(material);
        }
    }

    private static void fluffy(Material material, String... ids) {
        FLUFFY_BARREL_IDS.put(material, ids);
    }

    private record CandidateBlock(BlockVector3 source, BlockVector3 target, Material material) {}

    private static final class Stats {
        private long exactNbtRestored;
        private long knownInferred;
        private long genericInferred;
        private long betterChestsRefreshed;
        private long ambiguous;
        private long unavailable;
        private long exactUnavailable;
        private long exactMaterialMismatch;
        private long failed;

        private boolean hasOutput() {
            return exactNbtRestored + knownInferred + genericInferred + betterChestsRefreshed
                    + ambiguous + unavailable + exactUnavailable + exactMaterialMismatch + failed > 0;
        }
    }
}
