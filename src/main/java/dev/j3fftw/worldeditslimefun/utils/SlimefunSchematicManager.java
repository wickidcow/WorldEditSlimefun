package dev.j3fftw.worldeditslimefun.utils;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalConfiguration;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import dev.j3fftw.worldeditslimefun.WorldEditSlimefun;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.blocks.UnplaceableBlock;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slimefun-aware schematic support.
 *
 * <p>The normal Sponge schematic stores the physical structure, block-entity NBT/PDC and entities. A companion
 * <code>.wesf.yml</code> sidecar stores the Slimefun data that normally lives outside the world in Slimefun's
 * block database. This lets an administrative restore rebuild machines instead of leaving decorative shells.</p>
 */
@SuppressWarnings({"deprecation", "unchecked"})
public final class SlimefunSchematicManager {

    private static final int SIDECAR_SCHEMA = 1;
    private static final Map<UUID, LoadedSchematic> LOADED = new ConcurrentHashMap<>();

    private SlimefunSchematicManager() {}

    public static void save(@Nonnull Player player, @Nonnull String name, boolean overwrite) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        Actor actor = BukkitAdapter.adapt(player);
        LocalSession session = worldEdit.getSessionManager().get(actor);
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());

        final Region region;
        try {
            region = session.getSelection(world);
        } catch (IncompleteRegionException ex) {
            player.sendMessage(ChatColor.RED + "Select the backup area with WorldEdit/FAWE first.");
            return;
        }

        long maximum = WorldEditSlimefun.getInstance().getConfig().getLong("max-selection-blocks", 2_000_000L);
        if (maximum > 0 && region.getVolume() > maximum) {
            player.sendMessage(ChatColor.RED + "Selection is too large: " + region.getVolume() + " blocks (limit " + maximum + ").");
            return;
        }

        try {
            File schematicFile = resolveSchematicFile(actor, name, true);
            File sidecarFile = sidecarFor(schematicFile);
            if (!overwrite && (schematicFile.exists() || sidecarFile.exists())) {
                player.sendMessage(ChatColor.RED + "That schematic already exists.");
                player.sendMessage(ChatColor.GRAY + "Use /wesf schem save " + name + " true to overwrite it.");
                return;
            }

            File parent = schematicFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create schematic directory: " + parent);
            }

            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(session.getPlacementPosition(actor));

            try (EditSession source = worldEdit.newEditSession(world)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(source, region, clipboard, region.getMinimumPoint());
                copy.setCopyingEntities(true);
                Operations.completeLegacy(copy);
            }

            try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(schematicFile));
                 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(output)) {
                writer.write(clipboard);
            }

            List<SlimefunBlockRecord> records = snapshotSlimefunBlocks(player, region, clipboard.getOrigin());
            saveSidecar(sidecarFile, clipboard.getOrigin(), records);
            session.setClipboard(new ClipboardHolder(clipboard));
            LOADED.put(player.getUniqueId(), new LoadedSchematic(schematicFile.getName(), records, true));

            long normal = records.stream().filter(record -> !record.universal()).count();
            long universal = records.size() - normal;
            player.sendMessage(ChatColor.GREEN + "Saved Slimefun-aware schematic " + schematicFile.getName() + '.');
            player.sendMessage(ChatColor.GRAY + "Captured " + records.size() + " Slimefun blocks (" + normal
                    + " normal, " + universal + " universal/embedded).");
            player.sendMessage(ChatColor.GRAY + "Sidecar: " + sidecarFile.getName());
        } catch (Exception ex) {
            WorldEditSlimefun.getInstance().getLogger().severe("Failed to save schematic '" + name + "': " + ex.getMessage());
            ex.printStackTrace();
            player.sendMessage(ChatColor.RED + "Could not save that schematic. Check console for details.");
        }
    }

    public static void load(@Nonnull Player player, @Nonnull String name) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        Actor actor = BukkitAdapter.adapt(player);

        try {
            File schematicFile = resolveSchematicFile(actor, name, false);
            if (!schematicFile.exists()) {
                player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
                return;
            }

            ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
            if (format == null) {
                player.sendMessage(ChatColor.RED + "Could not determine schematic format for " + schematicFile.getName());
                return;
            }

            Clipboard clipboard;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(schematicFile));
                 ClipboardReader reader = format.getReader(input)) {
                clipboard = reader.read();
            }

            LocalSession session = worldEdit.getSessionManager().get(actor);
            session.setClipboard(new ClipboardHolder(clipboard));

            File sidecarFile = sidecarFor(schematicFile);
            List<SlimefunBlockRecord> records = sidecarFile.exists() ? loadSidecar(sidecarFile) : List.of();
            boolean hasSidecar = sidecarFile.exists();
            LOADED.put(player.getUniqueId(), new LoadedSchematic(schematicFile.getName(), records, hasSidecar));

            player.sendMessage(ChatColor.GREEN + "Loaded " + schematicFile.getName() + ".");
            if (hasSidecar) {
                player.sendMessage(ChatColor.GRAY + "Loaded " + records.size() + " saved Slimefun block records.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No WESF sidecar was found. /wesf paste will attempt a legacy PDC relink after pasting.");
            }
            player.sendMessage(ChatColor.GRAY + "Stand at the paste point, then run /wesf paste.");
        } catch (Exception ex) {
            WorldEditSlimefun.getInstance().getLogger().severe("Failed to load schematic '" + name + "': " + ex.getMessage());
            ex.printStackTrace();
            player.sendMessage(ChatColor.RED + "Could not load that schematic. Check console for details.");
        }
    }

    public static void paste(@Nonnull Player player) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        Actor actor = BukkitAdapter.adapt(player);
        LocalSession session = worldEdit.getSessionManager().get(actor);

        final ClipboardHolder holder;
        try {
            holder = session.getClipboard();
        } catch (EmptyClipboardException ex) {
            player.sendMessage(ChatColor.RED + "No schematic is loaded. Use /wesf schem load <name> first.");
            return;
        }

        Clipboard clipboard = holder.getClipboard();
        long maximum = WorldEditSlimefun.getInstance().getConfig().getLong("max-selection-blocks", 2_000_000L);
        if (maximum > 0 && clipboard.getRegion().getVolume() > maximum) {
            player.sendMessage(ChatColor.RED + "Schematic is too large: " + clipboard.getRegion().getVolume()
                    + " blocks (limit " + maximum + ").");
            return;
        }

        BlockVector3 destination = session.getPlacementPosition(actor);
        EditSession editSession = session.createEditSession(actor);
        try {
            Operation operation = holder.createPaste(editSession)
                    .to(destination)
                    .ignoreAirBlocks(false)
                    .copyEntities(true)
                    .build();
            Operations.completeLegacy(operation);
            editSession.flushSession();
            session.remember(editSession);
        } catch (WorldEditException | RuntimeException ex) {
            editSession.close();
            WorldEditSlimefun.getInstance().getLogger().severe("Failed to paste schematic: " + ex.getMessage());
            ex.printStackTrace();
            player.sendMessage(ChatColor.RED + "Schematic paste failed. Check console for details.");
            return;
        }

        LoadedSchematic loaded = LOADED.get(player.getUniqueId());
        RestoreStats stats;
        if (loaded != null && loaded.hasSidecar()) {
            stats = restoreRecords(player, holder, destination, loaded.records());
        } else {
            stats = relinkEmbeddedPdc(player, holder, destination);
        }

        player.sendMessage(ChatColor.GREEN + "Schematic pasted and Slimefun restore pass completed.");
        player.sendMessage(ChatColor.GRAY + "Restored: " + ChatColor.WHITE + stats.restored()
                + ChatColor.GRAY + " | already registered: " + ChatColor.WHITE + stats.alreadyRegistered()
                + ChatColor.GRAY + " | unknown: " + ChatColor.WHITE + stats.unknown()
                + ChatColor.GRAY + " | failed/unsupported: " + ChatColor.WHITE + stats.failed());
        if (loaded == null || !loaded.hasSidecar()) {
            player.sendMessage(ChatColor.YELLOW + "Legacy schematic mode can only restore Slimefun data that was embedded in the schematic itself.");
        }
        if (stats.restored() > 0) {
            player.sendMessage(ChatColor.YELLOW + "For a large recovery, restart the server normally after verifying the pasted area.");
        }
    }

    @Nonnull
    public static List<String> listSchematics() {
        File dir = schematicDirectory();
        File[] files = dir.listFiles((folder, fileName) -> fileName.endsWith(".schem") || fileName.endsWith(".schematic"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<String> names = new ArrayList<>(files.length);
        for (File file : files) {
            names.add(file.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Nonnull
    private static List<SlimefunBlockRecord> snapshotSlimefunBlocks(Player player, Region region, BlockVector3 origin) {
        List<SlimefunBlockRecord> records = new ArrayList<>();
        org.bukkit.World world = player.getWorld();

        for (BlockVector3 position : region) {
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            String sfId = BlockStorage.checkID(block.getLocation());
            if (sfId != null) {
                records.add(snapshotNormal(block, sfId, origin));
                continue;
            }

            SlimefunBlockRecord universal = snapshotUniversal(block, origin);
            if (universal != null) {
                records.add(universal);
                continue;
            }

            String embedded = findEmbeddedSlimefunId(block);
            if (embedded != null && SlimefunItem.getById(embedded) != null) {
                BlockVector3 relative = position.subtract(origin);
                records.add(new SlimefunBlockRecord(relative.x(), relative.y(), relative.z(), embedded,
                        true, Map.of(), Map.of()));
            }
        }

        return records;
    }

    private static SlimefunBlockRecord snapshotNormal(Block block, String sfId, BlockVector3 origin) {
        Map<String, String> data = new LinkedHashMap<>();
        Config info = BlockStorage.getLocationInfo(block.getLocation());
        for (String key : info.getKeys()) {
            if ("id".equals(key)) {
                continue;
            }
            String value = info.getString(key);
            if (value != null) {
                data.put(key, value);
            }
        }

        Map<Integer, ItemStack> inventory = snapshotMenu(BlockStorage.getInventory(block));
        BlockVector3 relative = BlockVector3.at(block.getX(), block.getY(), block.getZ()).subtract(origin);
        return new SlimefunBlockRecord(relative.x(), relative.y(), relative.z(), sfId, false, data, inventory);
    }

    @Nullable
    private static SlimefunBlockRecord snapshotUniversal(Block block, BlockVector3 origin) {
        try {
            Object controller = getLegacyBlockDataController();
            if (controller == null) {
                return null;
            }

            Method getter = controller.getClass().getMethod("getUniversalBlockDataFromCache", Location.class);
            Object result = getter.invoke(controller, block.getLocation());
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                return null;
            }

            Object universalData = optional.get();
            String sfId = (String) universalData.getClass().getMethod("getSfId").invoke(universalData);
            Map<String, String> data = new LinkedHashMap<>();
            Object allData = universalData.getClass().getMethod("getAllData").invoke(universalData);
            if (allData instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        data.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }

            Map<Integer, ItemStack> inventory = new LinkedHashMap<>();
            Object contents = universalData.getClass().getMethod("getMenuContents").invoke(universalData);
            if (contents instanceof ItemStack[] items) {
                for (int slot = 0; slot < items.length; slot++) {
                    if (items[slot] != null && !items[slot].getType().isAir()) {
                        inventory.put(slot, items[slot].clone());
                    }
                }
            }

            BlockVector3 relative = BlockVector3.at(block.getX(), block.getY(), block.getZ()).subtract(origin);
            return new SlimefunBlockRecord(relative.x(), relative.y(), relative.z(), sfId, true, data, inventory);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Map<Integer, ItemStack> snapshotMenu(@Nullable BlockMenu menu) {
        if (menu == null) {
            return Map.of();
        }

        Map<Integer, ItemStack> contents = new LinkedHashMap<>();
        Set<Integer> presetSlots = menu.getPreset().getPresetSlots();
        ItemStack[] inventory = menu.toInventory().getContents();
        for (int slot = 0; slot < inventory.length; slot++) {
            ItemStack item = inventory[slot];
            if (!presetSlots.contains(slot) && item != null && !item.getType().isAir()) {
                contents.put(slot, item.clone());
            }
        }
        return contents;
    }

    private static void saveSidecar(File file, BlockVector3 origin, List<SlimefunBlockRecord> records) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", SIDECAR_SCHEMA);
        yaml.set("origin.x", origin.x());
        yaml.set("origin.y", origin.y());
        yaml.set("origin.z", origin.z());

        List<Map<String, Object>> serialized = new ArrayList<>(records.size());
        for (SlimefunBlockRecord record : records) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", record.x());
            map.put("y", record.y());
            map.put("z", record.z());
            map.put("id", record.sfId());
            map.put("universal", record.universal());
            map.put("data", new LinkedHashMap<>(record.data()));

            Map<String, Object> inventory = new LinkedHashMap<>();
            for (Map.Entry<Integer, ItemStack> entry : record.inventory().entrySet()) {
                inventory.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            map.put("inventory", inventory);
            serialized.add(map);
        }
        yaml.set("blocks", serialized);
        yaml.save(file);
    }

    @Nonnull
    private static List<SlimefunBlockRecord> loadSidecar(File file) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int schema = yaml.getInt("schema", 0);
        if (schema != SIDECAR_SCHEMA) {
            throw new IOException("Unsupported WESF sidecar schema: " + schema);
        }

        List<SlimefunBlockRecord> records = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("blocks")) {
            int x = intValue(raw.get("x"));
            int y = intValue(raw.get("y"));
            int z = intValue(raw.get("z"));
            String sfId = String.valueOf(raw.get("id"));
            boolean universal = Boolean.parseBoolean(String.valueOf(raw.getOrDefault("universal", false)));

            Map<String, String> data = new LinkedHashMap<>();
            Object rawData = raw.get("data");
            if (rawData instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        data.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }

            Map<Integer, ItemStack> inventory = new LinkedHashMap<>();
            Object rawInventory = raw.get("inventory");
            if (rawInventory instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    try {
                        int slot = Integer.parseInt(String.valueOf(entry.getKey()));
                        if (entry.getValue() instanceof ItemStack item) {
                            inventory.put(slot, item.clone());
                        } else if (entry.getValue() instanceof Map<?, ?> itemMap) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> itemEntry : itemMap.entrySet()) {
                                if (itemEntry.getKey() != null) {
                                    values.put(String.valueOf(itemEntry.getKey()), itemEntry.getValue());
                                }
                            }
                            inventory.put(slot, ItemStack.deserialize(values));
                        }
                    } catch (RuntimeException ignored) {
                        // A corrupt slot should not make an otherwise useful recovery unusable.
                    }
                }
            }

            records.add(new SlimefunBlockRecord(x, y, z, sfId, universal, data, inventory));
        }
        return records;
    }

    private static RestoreStats restoreRecords(Player player, ClipboardHolder holder, BlockVector3 destination,
                                                List<SlimefunBlockRecord> records) {
        long restored = 0;
        long already = 0;
        long unknown = 0;
        long failed = 0;

        for (SlimefunBlockRecord record : records) {
            SlimefunItem sfItem = SlimefunItem.getById(record.sfId());
            if (sfItem == null || sfItem instanceof UnplaceableBlock) {
                unknown++;
                continue;
            }

            BlockVector3 target = transformRelative(holder, destination, BlockVector3.at(record.x(), record.y(), record.z()));
            Block block = player.getWorld().getBlockAt(target.x(), target.y(), target.z());

            String existing = BlockStorage.checkID(block.getLocation());
            if (existing != null && existing.equals(record.sfId()) && !record.universal()) {
                already++;
                reapplyNormalState(block, record);
                continue;
            }

            try {
                if (BlockStorage.hasBlockInfo(block)) {
                    BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);
                }

                boolean createdUniversal = record.universal() && restoreUniversal(block, record);
                if (!createdUniversal) {
                    BlockStorage.store(block, record.sfId());
                    callPlaceHandler(player, block, sfItem);
                    reapplyNormalState(block, record);
                } else {
                    callPlaceHandlerSafely(player, block, sfItem);
                }
                restored++;
            } catch (Exception | LinkageError ex) {
                failed++;
                WorldEditSlimefun.getInstance().getLogger().warning("Could not restore " + record.sfId() + " at "
                        + block.getX() + ',' + block.getY() + ',' + block.getZ() + ": " + ex.getMessage());
            }
        }

        return new RestoreStats(restored, already, unknown, failed);
    }

    private static void reapplyNormalState(Block block, SlimefunBlockRecord record) {
        for (Map.Entry<String, String> entry : record.data().entrySet()) {
            BlockStorage.addBlockInfo(block.getLocation(), entry.getKey(), entry.getValue());
        }

        BlockMenu menu = BlockStorage.getInventory(block);
        if (menu != null) {
            for (Map.Entry<Integer, ItemStack> entry : record.inventory().entrySet()) {
                if (entry.getKey() >= 0 && entry.getKey() < menu.toInventory().getSize()) {
                    menu.replaceExistingItem(entry.getKey(), entry.getValue().clone(), false);
                }
            }
            menu.save(block.getLocation());
        }
    }

    private static boolean restoreUniversal(Block block, SlimefunBlockRecord record) {
        try {
            Object controller = getLegacyBlockDataController();
            if (controller == null) {
                return false;
            }

            Method creator = controller.getClass().getMethod("createUniversalBlock", Location.class, String.class);
            Object universalData = creator.invoke(controller, block.getLocation(), record.sfId());
            Method setData = universalData.getClass().getMethod("setData", String.class, String.class);
            for (Map.Entry<String, String> entry : record.data().entrySet()) {
                try {
                    setData.invoke(universalData, entry.getKey(), entry.getValue());
                } catch (InvocationTargetException ignored) {
                    // Reserved universal keys (location/traits) are regenerated for the new destination.
                }
            }

            Object menu = universalData.getClass().getMethod("getMenu").invoke(universalData);
            if (menu != null) {
                Method replace = menu.getClass().getMethod("replaceExistingItem", int.class, ItemStack.class, boolean.class);
                for (Map.Entry<Integer, ItemStack> entry : record.inventory().entrySet()) {
                    replace.invoke(menu, entry.getKey(), entry.getValue().clone(), false);
                }
            }
            return true;
        } catch (NoSuchMethodException | ClassNotFoundException ex) {
            return false;
        } catch (ReflectiveOperationException ex) {
            Throwable cause = ex instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : ex;
            throw new IllegalStateException("Universal Slimefun restore failed: " + cause.getMessage(), cause);
        }
    }

    private static RestoreStats relinkEmbeddedPdc(Player player, ClipboardHolder holder, BlockVector3 destination) {
        long restored = 0;
        long already = 0;
        long unknown = 0;
        long failed = 0;

        Clipboard clipboard = holder.getClipboard();
        for (BlockVector3 source : clipboard.getRegion()) {
            BlockVector3 relative = source.subtract(clipboard.getOrigin());
            BlockVector3 target = transformRelative(holder, destination, relative);
            Block block = player.getWorld().getBlockAt(target.x(), target.y(), target.z());
            String sfId = findEmbeddedSlimefunId(block);
            if (sfId == null) {
                continue;
            }

            SlimefunItem sfItem = SlimefunItem.getById(sfId);
            if (sfItem == null || sfItem instanceof UnplaceableBlock) {
                unknown++;
                continue;
            }

            String existing = BlockStorage.checkID(block.getLocation());
            if (sfId.equals(existing)) {
                already++;
                continue;
            }

            try {
                if (existing != null) {
                    BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);
                }
                try {
                    BlockStorage.store(block, sfId);
                    callPlaceHandler(player, block, sfItem);
                } catch (IllegalArgumentException normalFailure) {
                    SlimefunBlockRecord universal = new SlimefunBlockRecord(relative.x(), relative.y(), relative.z(),
                            sfId, true, Map.of(), Map.of());
                    if (!restoreUniversal(block, universal)) {
                        throw normalFailure;
                    }
                    callPlaceHandlerSafely(player, block, sfItem);
                }
                restored++;
            } catch (Exception | LinkageError ex) {
                failed++;
            }
        }

        return new RestoreStats(restored, already, unknown, failed);
    }

    @Nullable
    private static String findEmbeddedSlimefunId(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tileState)) {
            return null;
        }

        PersistentDataContainer pdc = tileState.getPersistentDataContainer();
        String fallback = null;
        for (NamespacedKey key : pdc.getKeys()) {
            if (!"slimefun".equalsIgnoreCase(key.getNamespace())) {
                continue;
            }
            String path = key.getKey();
            if (!"slimefun_block".equalsIgnoreCase(path) && !"slimefun_item".equalsIgnoreCase(path)) {
                continue;
            }
            if (!pdc.has(key, PersistentDataType.STRING)) {
                continue;
            }
            String value = pdc.get(key, PersistentDataType.STRING);
            if (value == null || value.isBlank()) {
                continue;
            }
            if ("slimefun_block".equalsIgnoreCase(path)) {
                return value;
            }
            fallback = value;
        }
        return fallback;
    }

    private static void callPlaceHandler(Player player, Block block, SlimefunItem sfItem) {
        ItemStack item = sfItem.getItem();
        sfItem.callItemHandler(BlockPlaceHandler.class, handler -> {
            BlockPlaceEvent event = new BlockPlaceEvent(
                    block,
                    block.getState(),
                    block.getRelative(BlockFace.DOWN),
                    item,
                    player,
                    true,
                    EquipmentSlot.HAND);
            handler.onPlayerPlace(event);
        });
    }

    private static void callPlaceHandlerSafely(Player player, Block block, SlimefunItem sfItem) {
        try {
            callPlaceHandler(player, block, sfItem);
        } catch (RuntimeException | LinkageError ex) {
            WorldEditSlimefun.getInstance().getLogger().warning("Placement hook failed while restoring "
                    + sfItem.getId() + " at " + block.getLocation() + ": " + ex.getMessage());
        }
    }

    private static BlockVector3 transformRelative(ClipboardHolder holder, BlockVector3 destination, BlockVector3 relative) {
        Vector3 transformed = holder.getTransform().apply(relative.toVector3());
        return destination.toVector3().add(transformed).toBlockPoint();
    }

    @Nullable
    private static Object getLegacyBlockDataController() throws ReflectiveOperationException, ClassNotFoundException {
        Class<?> slimefunClass = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
        Method databaseManager = slimefunClass.getMethod("getDatabaseManager");
        Object manager = databaseManager.invoke(null);
        if (manager == null) {
            return null;
        }
        return manager.getClass().getMethod("getBlockDataController").invoke(manager);
    }

    private static File resolveSchematicFile(Actor actor, String name, boolean save) throws Exception {
        WorldEdit worldEdit = WorldEdit.getInstance();
        File dir = schematicDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create schematic directory: " + dir);
        }

        if (save) {
            return worldEdit.getSafeSaveFile(actor, dir, name, "schem");
        }
        return worldEdit.getSafeOpenFile(actor, dir, name, "schem", ClipboardFormats.getFileExtensionArray());
    }

    private static File schematicDirectory() {
        WorldEdit worldEdit = WorldEdit.getInstance();
        LocalConfiguration config = worldEdit.getConfiguration();
        return worldEdit.getWorkingDirectoryPath(config.saveDir).toFile();
    }

    private static File sidecarFor(File schematic) {
        String name = schematic.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(schematic.getParentFile(), base + ".wesf.yml");
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private record LoadedSchematic(String name, List<SlimefunBlockRecord> records, boolean hasSidecar) {}

    private record SlimefunBlockRecord(
            int x,
            int y,
            int z,
            String sfId,
            boolean universal,
            Map<String, String> data,
            Map<Integer, ItemStack> inventory) {}

    private record RestoreStats(long restored, long alreadyRegistered, long unknown, long failed) {}
}
