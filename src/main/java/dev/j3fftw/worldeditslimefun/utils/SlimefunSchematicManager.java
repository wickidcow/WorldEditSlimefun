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
 * Slimefun-aware Sponge schematic support.
 *
 * <p>The .schem stores the physical WorldEdit/FAWE clipboard. A companion .wesf.yml stores the Slimefun
 * records and non-preset menu contents which normally live outside Minecraft's block NBT.</p>
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
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(player.getWorld());

        final Region region;
        try {
            region = session.getSelection(weWorld);
        } catch (IncompleteRegionException ex) {
            player.sendMessage(ChatColor.RED + "Select the backup area with WorldEdit/FAWE first.");
            return;
        }

        if (!withinLimit(player, region.getVolume())) {
            return;
        }

        try {
            File schematic = resolveSchematicFile(actor, name, true);
            File sidecar = sidecarFor(schematic);
            if (!overwrite && (schematic.exists() || sidecar.exists())) {
                player.sendMessage(ChatColor.RED + "That schematic already exists.");
                player.sendMessage(ChatColor.GRAY + "Use /wesf schem save " + name + " true to overwrite it.");
                return;
            }

            File parent = schematic.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create schematic directory: " + parent);
            }

            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(session.getPlacementPosition(actor));
            try (EditSession source = worldEdit.newEditSession(weWorld)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(source, region, clipboard, region.getMinimumPoint());
                copy.setCopyingEntities(true);
                Operations.completeLegacy(copy);
            }

            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(schematic));
                 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
                writer.write(clipboard);
            }

            List<SfRecord> records = snapshot(player, region, clipboard.getOrigin());
            writeSidecar(sidecar, clipboard.getOrigin(), records);
            session.setClipboard(new ClipboardHolder(clipboard));
            LOADED.put(player.getUniqueId(), new LoadedSchematic(schematic.getName(), records, true));

            long universal = records.stream().filter(SfRecord::universal).count();
            player.sendMessage(ChatColor.GREEN + "Saved " + schematic.getName() + " with Slimefun recovery data.");
            player.sendMessage(ChatColor.GRAY + "Captured " + records.size() + " Slimefun blocks ("
                    + (records.size() - universal) + " normal, " + universal + " universal/embedded).");
            player.sendMessage(ChatColor.GRAY + "Recovery sidecar: " + sidecar.getName());
        } catch (Exception ex) {
            logFailure("save schematic '" + name + "'", ex);
            player.sendMessage(ChatColor.RED + "Could not save that schematic. Check console for details.");
        }
    }

    public static void load(@Nonnull Player player, @Nonnull String name) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        Actor actor = BukkitAdapter.adapt(player);
        try {
            File schematic = resolveSchematicFile(actor, name, false);
            if (!schematic.exists()) {
                player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
                return;
            }

            ClipboardFormat format = ClipboardFormats.findByFile(schematic);
            if (format == null) {
                player.sendMessage(ChatColor.RED + "Unknown schematic format: " + schematic.getName());
                return;
            }

            Clipboard clipboard;
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(schematic));
                 ClipboardReader reader = format.getReader(in)) {
                clipboard = reader.read();
            }

            LocalSession session = worldEdit.getSessionManager().get(actor);
            session.setClipboard(new ClipboardHolder(clipboard));

            File sidecar = sidecarFor(schematic);
            boolean hasSidecar = sidecar.exists();
            List<SfRecord> records = hasSidecar ? readSidecar(sidecar) : List.of();
            LOADED.put(player.getUniqueId(), new LoadedSchematic(schematic.getName(), records, hasSidecar));

            player.sendMessage(ChatColor.GREEN + "Loaded " + schematic.getName() + '.');
            if (hasSidecar) {
                player.sendMessage(ChatColor.GRAY + "Loaded " + records.size() + " Slimefun recovery records.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No .wesf.yml sidecar found. Legacy embedded-PDC relink mode will be used.");
            }
            player.sendMessage(ChatColor.GRAY + "Stand at the paste point and run /wesf paste.");
        } catch (Exception ex) {
            logFailure("load schematic '" + name + "'", ex);
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
        if (!withinLimit(player, clipboard.getRegion().getVolume())) {
            return;
        }

        BlockVector3 destination = session.getPlacementPosition(actor);
        EditSession editSession = session.createEditSession(actor);
        try {
            Operation paste = holder.createPaste(editSession)
                    .to(destination)
                    .ignoreAirBlocks(false)
                    .copyEntities(true)
                    .build();
            Operations.completeLegacy(paste);
            editSession.flushSession();
            session.remember(editSession);
        } catch (WorldEditException | RuntimeException ex) {
            editSession.close();
            logFailure("paste schematic", ex);
            player.sendMessage(ChatColor.RED + "Schematic paste failed. Check console for details.");
            return;
        }

        LoadedSchematic loaded = LOADED.get(player.getUniqueId());
        RestoreStats stats = loaded != null && loaded.hasSidecar()
                ? restoreRecords(player, holder, destination, loaded.records())
                : relinkEmbeddedPdc(player, holder, destination);

        player.sendMessage(ChatColor.GREEN + "Schematic pasted and Slimefun restore pass completed.");
        player.sendMessage(ChatColor.GRAY + "Restored: " + ChatColor.WHITE + stats.restored()
                + ChatColor.GRAY + " | already registered: " + ChatColor.WHITE + stats.already()
                + ChatColor.GRAY + " | unknown: " + ChatColor.WHITE + stats.unknown()
                + ChatColor.GRAY + " | failed/unsupported: " + ChatColor.WHITE + stats.failed());
        if (loaded == null || !loaded.hasSidecar()) {
            player.sendMessage(ChatColor.YELLOW + "Legacy schematic mode can only rebuild state that was embedded in the old schematic.");
        }
        if (stats.restored() > 0) {
            player.sendMessage(ChatColor.YELLOW + "After verifying a large restore, restart the server normally so tickers/networks reload cleanly.");
        }
    }

    @Nonnull
    public static List<String> listSchematics() {
        File[] files = schematicDirectory().listFiles((dir, fileName) -> {
            String lower = fileName.toLowerCase();
            return lower.endsWith(".schem") || lower.endsWith(".schematic");
        });
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<String> result = new ArrayList<>(files.length);
        for (File file : files) {
            result.add(file.getName());
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static boolean withinLimit(Player player, long volume) {
        long maximum = WorldEditSlimefun.getInstance().getConfig().getLong("max-selection-blocks", 2_000_000L);
        if (maximum > 0 && volume > maximum) {
            player.sendMessage(ChatColor.RED + "Area is too large: " + volume + " blocks (limit " + maximum + ").");
            player.sendMessage(ChatColor.GRAY + "Raise max-selection-blocks in plugins/WorldEditSlimefun/config.yml if needed.");
            return false;
        }
        return true;
    }

    private static List<SfRecord> snapshot(Player player, Region region, BlockVector3 origin) {
        List<SfRecord> records = new ArrayList<>();
        org.bukkit.World world = player.getWorld();
        for (BlockVector3 position : region) {
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            String sfId = BlockStorage.checkID(block.getLocation());
            if (sfId != null) {
                records.add(snapshotNormal(block, sfId, origin));
                continue;
            }

            SfRecord universal = snapshotUniversal(block, origin);
            if (universal != null) {
                records.add(universal);
                continue;
            }

            String embedded = findEmbeddedSlimefunId(block);
            if (embedded != null && SlimefunItem.getById(embedded) != null) {
                BlockVector3 relative = position.subtract(origin);
                records.add(new SfRecord(relative.x(), relative.y(), relative.z(), embedded, true, Map.of(), Map.of()));
            }
        }
        return records;
    }

    private static SfRecord snapshotNormal(Block block, String sfId, BlockVector3 origin) {
        Map<String, String> data = new LinkedHashMap<>();
        Config info = BlockStorage.getLocationInfo(block.getLocation());
        for (String key : info.getKeys()) {
            if (!"id".equals(key)) {
                String value = info.getString(key);
                if (value != null) {
                    data.put(key, value);
                }
            }
        }

        BlockVector3 relative = BlockVector3.at(block.getX(), block.getY(), block.getZ()).subtract(origin);
        return new SfRecord(relative.x(), relative.y(), relative.z(), sfId, false, data,
                snapshotMenu(BlockStorage.getInventory(block)));
    }

    @Nullable
    private static SfRecord snapshotUniversal(Block block, BlockVector3 origin) {
        try {
            Object controller = legacyController();
            if (controller == null) {
                return null;
            }

            Method getter = controller.getClass().getMethod("getUniversalBlockDataFromCache", Location.class);
            Object raw = getter.invoke(controller, block.getLocation());
            if (!(raw instanceof Optional<?> optional) || optional.isEmpty()) {
                return null;
            }

            Object universal = optional.get();
            String sfId = String.valueOf(universal.getClass().getMethod("getSfId").invoke(universal));
            Map<String, String> data = new LinkedHashMap<>();
            Object allData = universal.getClass().getMethod("getAllData").invoke(universal);
            if (allData instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        data.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }

            Map<Integer, ItemStack> inventory = new LinkedHashMap<>();
            Object contents = universal.getClass().getMethod("getMenuContents").invoke(universal);
            if (contents instanceof ItemStack[] items) {
                for (int slot = 0; slot < items.length; slot++) {
                    ItemStack item = items[slot];
                    if (item != null && !item.getType().isAir()) {
                        inventory.put(slot, item.clone());
                    }
                }
            }

            BlockVector3 relative = BlockVector3.at(block.getX(), block.getY(), block.getZ()).subtract(origin);
            return new SfRecord(relative.x(), relative.y(), relative.z(), sfId, true, data, inventory);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Map<Integer, ItemStack> snapshotMenu(@Nullable BlockMenu menu) {
        if (menu == null) {
            return Map.of();
        }

        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        Set<Integer> presetSlots = menu.getPreset().getPresetSlots();
        ItemStack[] contents = menu.toInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!presetSlots.contains(slot) && item != null && !item.getType().isAir()) {
                result.put(slot, item.clone());
            }
        }
        return result;
    }

    private static void writeSidecar(File file, BlockVector3 origin, List<SfRecord> records) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", SIDECAR_SCHEMA);
        yaml.set("origin.x", origin.x());
        yaml.set("origin.y", origin.y());
        yaml.set("origin.z", origin.z());

        List<Map<String, Object>> blocks = new ArrayList<>(records.size());
        for (SfRecord record : records) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("x", record.x());
            block.put("y", record.y());
            block.put("z", record.z());
            block.put("id", record.sfId());
            block.put("universal", record.universal());
            block.put("data", new LinkedHashMap<>(record.data()));

            Map<String, Object> inventory = new LinkedHashMap<>();
            record.inventory().forEach((slot, item) -> inventory.put(String.valueOf(slot), item));
            block.put("inventory", inventory);
            blocks.add(block);
        }
        yaml.set("blocks", blocks);
        yaml.save(file);
    }

    private static List<SfRecord> readSidecar(File file) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int schema = yaml.getInt("schema", 0);
        if (schema != SIDECAR_SCHEMA) {
            throw new IOException("Unsupported WESF sidecar schema: " + schema);
        }

        List<SfRecord> result = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("blocks")) {
            int x = intValue(raw.get("x"));
            int y = intValue(raw.get("y"));
            int z = intValue(raw.get("z"));
            String sfId = String.valueOf(raw.get("id"));
            Object universalValue = raw.containsKey("universal") ? raw.get("universal") : Boolean.FALSE;
            boolean universal = Boolean.parseBoolean(String.valueOf(universalValue));

            Map<String, String> data = stringMap(raw.get("data"));
            Map<Integer, ItemStack> inventory = itemMap(raw.get("inventory"));
            result.add(new SfRecord(x, y, z, sfId, universal, data, inventory));
        }
        return result;
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    private static Map<Integer, ItemStack> itemMap(Object raw) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return result;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            try {
                int slot = Integer.parseInt(String.valueOf(entry.getKey()));
                Object value = entry.getValue();
                if (value instanceof ItemStack item) {
                    result.put(slot, item.clone());
                } else if (value instanceof Map<?, ?> serialized) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    serialized.forEach((key, itemValue) -> {
                        if (key != null) {
                            values.put(String.valueOf(key), itemValue);
                        }
                    });
                    result.put(slot, ItemStack.deserialize(values));
                }
            } catch (RuntimeException ignored) {
                // Skip a corrupt inventory slot without discarding the rest of the backup.
            }
        }
        return result;
    }

    private static RestoreStats restoreRecords(Player player, ClipboardHolder holder, BlockVector3 destination,
                                                List<SfRecord> records) {
        long restored = 0;
        long already = 0;
        long unknown = 0;
        long failed = 0;

        for (SfRecord record : records) {
            SlimefunItem sfItem = SlimefunItem.getById(record.sfId());
            if (sfItem == null || sfItem instanceof UnplaceableBlock) {
                unknown++;
                continue;
            }

            BlockVector3 target = transformRelative(holder, destination, BlockVector3.at(record.x(), record.y(), record.z()));
            Block block = player.getWorld().getBlockAt(target.x(), target.y(), target.z());
            String existing = BlockStorage.checkID(block.getLocation());

            if (!record.universal() && record.sfId().equals(existing)) {
                already++;
                reapplyNormalState(block, record);
                continue;
            }

            try {
                if (BlockStorage.hasBlockInfo(block)) {
                    BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);
                }

                if (record.universal() && restoreUniversal(block, record)) {
                    callPlaceHandlerSafely(player, block, sfItem);
                } else {
                    BlockStorage.store(block, record.sfId());
                    callPlaceHandler(player, block, sfItem);
                    reapplyNormalState(block, record);
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

    private static void reapplyNormalState(Block block, SfRecord record) {
        record.data().forEach((key, value) -> BlockStorage.addBlockInfo(block.getLocation(), key, value));
        BlockMenu menu = BlockStorage.getInventory(block);
        if (menu == null) {
            return;
        }

        int size = menu.toInventory().getSize();
        for (Map.Entry<Integer, ItemStack> entry : record.inventory().entrySet()) {
            if (entry.getKey() >= 0 && entry.getKey() < size) {
                menu.replaceExistingItem(entry.getKey(), entry.getValue().clone(), false);
            }
        }
        menu.save(block.getLocation());
    }

    private static boolean restoreUniversal(Block block, SfRecord record) {
        try {
            Object controller = legacyController();
            if (controller == null) {
                return false;
            }

            Object data = controller.getClass()
                    .getMethod("createUniversalBlock", Location.class, String.class)
                    .invoke(controller, block.getLocation(), record.sfId());
            Method setData = data.getClass().getMethod("setData", String.class, String.class);
            for (Map.Entry<String, String> entry : record.data().entrySet()) {
                try {
                    setData.invoke(data, entry.getKey(), entry.getValue());
                } catch (InvocationTargetException ignored) {
                    // Reserved universal keys are regenerated for the new destination/UUID.
                }
            }

            Object menu = data.getClass().getMethod("getMenu").invoke(data);
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
                    SfRecord universal = new SfRecord(relative.x(), relative.y(), relative.z(), sfId,
                            true, Map.of(), Map.of());
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
        sfItem.callItemHandler(BlockPlaceHandler.class, handler -> handler.onPlayerPlace(new BlockPlaceEvent(
                block,
                block.getState(),
                block.getRelative(BlockFace.DOWN),
                item,
                player,
                true,
                EquipmentSlot.HAND)));
    }

    private static void callPlaceHandlerSafely(Player player, Block block, SlimefunItem sfItem) {
        try {
            callPlaceHandler(player, block, sfItem);
        } catch (RuntimeException | LinkageError ex) {
            WorldEditSlimefun.getInstance().getLogger().warning("Placement hook failed for " + sfItem.getId()
                    + " at " + block.getLocation() + ": " + ex.getMessage());
        }
    }

    private static BlockVector3 transformRelative(ClipboardHolder holder, BlockVector3 destination, BlockVector3 relative) {
        Vector3 transformed = holder.getTransform().apply(relative.toVector3());
        return destination.toVector3().add(transformed).toBlockPoint();
    }

    @Nullable
    private static Object legacyController() throws ReflectiveOperationException, ClassNotFoundException {
        Class<?> slimefun = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
        Object database = slimefun.getMethod("getDatabaseManager").invoke(null);
        return database == null ? null : database.getClass().getMethod("getBlockDataController").invoke(database);
    }

    private static File resolveSchematicFile(Actor actor, String name, boolean save) throws Exception {
        WorldEdit worldEdit = WorldEdit.getInstance();
        File directory = schematicDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create schematic directory: " + directory);
        }
        return save
                ? worldEdit.getSafeSaveFile(actor, directory, name, "schem")
                : worldEdit.getSafeOpenFile(actor, directory, name, "schem", ClipboardFormats.getFileExtensionArray());
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
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static void logFailure(String operation, Throwable throwable) {
        WorldEditSlimefun.getInstance().getLogger().severe("Failed to " + operation + ": " + throwable.getMessage());
        throwable.printStackTrace();
    }

    private record LoadedSchematic(String name, List<SfRecord> records, boolean hasSidecar) {}

    private record SfRecord(int x, int y, int z, String sfId, boolean universal,
                            Map<String, String> data, Map<Integer, ItemStack> inventory) {}

    private record RestoreStats(long restored, long already, long unknown, long failed) {}
}
