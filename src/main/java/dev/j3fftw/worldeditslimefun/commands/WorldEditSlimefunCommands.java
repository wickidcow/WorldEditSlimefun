package dev.j3fftw.worldeditslimefun.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.BukkitCommandExecutionContext;
import co.aikar.commands.CommandCompletions;
import co.aikar.commands.CommandContexts;
import co.aikar.commands.PaperCommandManager;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandCompletion;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Subcommand;
import dev.j3fftw.worldeditslimefun.WorldEditSlimefun;
import dev.j3fftw.worldeditslimefun.commands.flags.CommandFlag;
import dev.j3fftw.worldeditslimefun.commands.flags.CommandFlags;
import dev.j3fftw.worldeditslimefun.utils.PositionManager;
import dev.j3fftw.worldeditslimefun.utils.SelectionResolver;
import dev.j3fftw.worldeditslimefun.utils.SelectionResolver.Selection;
import dev.j3fftw.worldeditslimefun.utils.Utils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.blocks.UnplaceableBlock;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings({"unused", "deprecation"})
@CommandAlias("wesf|sfedit")
@CommandPermission("wesf.admin")
public class WorldEditSlimefunCommands extends BaseCommand {

    public static void init(WorldEditSlimefun plugin) {
        PaperCommandManager manager = new PaperCommandManager(plugin);
        CommandCompletions<BukkitCommandCompletionContext> completions = manager.getCommandCompletions();
        CommandContexts<BukkitCommandExecutionContext> contexts = manager.getCommandContexts();

        completions.registerStaticCompletion("slimefun_blocks", Utils.SLIMEFUN_BLOCKS);
        completions.registerAsyncCompletion("command_flags", context -> {
            List<String> args = new ArrayList<>(Arrays.asList(context.getContextValueByName(String[].class, "commandFlags")));
            List<String> availableFlags = new ArrayList<>(CommandFlags.getFlagTypes().keySet());
            availableFlags.removeAll(args);

            if (args.isEmpty()) {
                return availableFlags;
            }

            args.remove(args.size() - 1);
            if (args.isEmpty()) {
                return availableFlags;
            }

            String lastArg = args.get(args.size() - 1);
            if (CommandFlags.getFlagTypes().containsKey(lastArg)) {
                return CommandFlags.getFlagTypes().get(lastArg).getTabSuggestions(context);
            }

            if (args.size() % 2 == 0) {
                return availableFlags;
            }

            return List.of("invalid_flag");
        });

        manager.registerCommand(new WorldEditSlimefunCommands());
    }

    @Default
    public void onDefault(Player player) {
        player.sendMessage(ChatColor.RED + "Please provide a valid subcommand.");
        player.sendMessage(ChatColor.GRAY + "Use your WorldEdit/FAWE selection for paste, clear, audit and recover.");
    }

    @Subcommand("wand")
    public void onWand(Player player) {
        ItemStack wand = SlimefunItem.getOptionalById("WESF_WAND").map(SlimefunItem::getItem).orElse(null);

        if (wand == null) {
            player.sendMessage(ChatColor.RED + "Wand not found!");
            return;
        }

        player.getInventory().addItem(wand);
    }

    @Subcommand("pos1")
    public void onPos1(Player player) {
        PositionManager.addPositionOne(player);
    }

    @Subcommand("pos2")
    public void onPos2(Player player) {
        PositionManager.addPositionTwo(player);
    }

    @Subcommand("paste")
    @CommandCompletion("@slimefun_blocks @command_flags")
    public void paste(Player player, @Default("INVALID") String sfId, String[] commandFlags) {
        Selection selection = requireSelection(player);
        if (selection == null || !isSelectionAllowed(player, selection)) {
            return;
        }

        SlimefunItem sfItem = SlimefunItem.getById(sfId);
        if (sfItem == null || sfItem instanceof UnplaceableBlock) {
            player.sendMessage(ChatColor.RED + "Invalid Slimefun item!");
            return;
        }

        List<CommandFlag<?>> flags = CommandFlags.getFlags(Arrays.asList(commandFlags));
        flags.removeIf(flag -> flag == null || !flag.canApply(sfItem));

        ItemStack item = sfItem.getItem();
        long start = System.currentTimeMillis();
        long amountOfBlocks = loopThroughSelection(selection, block -> {
            if (BlockStorage.hasBlockInfo(block)) {
                BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);
            }

            block.setType(item.getType(), false);
            BlockStorage.store(block, sfId);
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

            for (CommandFlag<?> flag : flags) {
                flag.apply(player, flags, sfItem, block);
            }
        });
        long time = System.currentTimeMillis() - start;

        player.sendMessage("Pasted " + amountOfBlocks + " " + sfItem.getItemName() + ChatColor.WHITE + " block(s)");
        player.sendMessage("Took " + time + "ms to paste!");
    }

    @Subcommand("clear")
    @CommandCompletion("true|false")
    public void clear(Player player, @Default("false") boolean callEvent) {
        Selection selection = requireSelection(player);
        if (selection == null || !isSelectionAllowed(player, selection)) {
            return;
        }

        long start = System.currentTimeMillis();
        long amountOfBlocks = loopThroughSelection(selection, block -> {
            if (callEvent && BlockStorage.hasBlockInfo(block)) {
                SlimefunItem sfItem = BlockStorage.check(block);
                if (sfItem != null) {
                    sfItem.callItemHandler(BlockBreakHandler.class, handler -> {
                        BlockBreakEvent event = new BlockBreakEvent(block, player);
                        handler.onPlayerBreak(event, new ItemStack(Material.AIR), new ArrayList<>());
                    });
                }
            }
            block.setType(Material.AIR, false);
            BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);
        });
        long time = System.currentTimeMillis() - start;

        player.sendMessage("Cleared " + amountOfBlocks + " blocks");
        player.sendMessage("Took " + time + "ms to clear!");
    }

    /**
     * Audits the active WorldEdit/FAWE selection for surviving Slimefun storage records.
     * This is useful before attempting recovery of an area previously cleared by WorldEdit/FAWE.
     */
    @Subcommand("audit")
    public void audit(Player player) {
        Selection selection = requireSelection(player);
        if (selection == null || !isSelectionAllowed(player, selection)) {
            return;
        }

        long start = System.currentTimeMillis();
        long[] counts = new long[4];
        loopThroughSelection(selection, block -> {
            String sfId = BlockStorage.checkID(block.getLocation());
            if (sfId == null) {
                return;
            }

            counts[0]++;
            SlimefunItem sfItem = SlimefunItem.getById(sfId);
            if (sfItem == null) {
                counts[3]++;
                return;
            }

            Material expected = sfItem.getItem().getType();
            if (block.getType().isAir()) {
                counts[1]++;
            } else if (block.getType() != expected) {
                counts[2]++;
            }
        });

        player.sendMessage(ChatColor.GOLD + "Slimefun recovery audit:");
        player.sendMessage(ChatColor.GRAY + "Stored records: " + ChatColor.WHITE + counts[0]);
        player.sendMessage(ChatColor.GRAY + "Records currently in air: " + ChatColor.WHITE + counts[1]);
        player.sendMessage(ChatColor.GRAY + "Material mismatches: " + ChatColor.WHITE + counts[2]);
        player.sendMessage(ChatColor.GRAY + "Unknown Slimefun IDs: " + ChatColor.WHITE + counts[3]);
        player.sendMessage(ChatColor.GRAY + "Scan time: " + ChatColor.WHITE + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Recreates the vanilla block material for surviving Slimefun records in air.
     * Existing Slimefun storage is intentionally preserved; this command does not invent
     * missing IDs or overwrite saved inventories/data.
     *
     * @param force when true, also replaces non-air material mismatches
     */
    @Subcommand("recover")
    @CommandCompletion("false|true")
    public void recover(Player player, @Default("false") boolean force) {
        Selection selection = requireSelection(player);
        if (selection == null || !isSelectionAllowed(player, selection)) {
            return;
        }

        long start = System.currentTimeMillis();
        long[] counts = new long[4];
        loopThroughSelection(selection, block -> {
            String sfId = BlockStorage.checkID(block.getLocation());
            if (sfId == null) {
                return;
            }

            SlimefunItem sfItem = SlimefunItem.getById(sfId);
            if (sfItem == null || sfItem instanceof UnplaceableBlock) {
                counts[2]++;
                return;
            }

            Material expected = sfItem.getItem().getType();
            if (!expected.isBlock()) {
                counts[2]++;
                return;
            }

            if (block.getType() == expected) {
                counts[1]++;
                return;
            }

            if (block.getType().isAir() || force) {
                block.setType(expected, false);
                counts[0]++;
            } else {
                counts[3]++;
            }
        });

        player.sendMessage(ChatColor.GREEN + "Slimefun recovery complete.");
        player.sendMessage(ChatColor.GRAY + "Restored physical Slimefun blocks: " + ChatColor.WHITE + counts[0]);
        player.sendMessage(ChatColor.GRAY + "Already matched: " + ChatColor.WHITE + counts[1]);
        player.sendMessage(ChatColor.GRAY + "Unknown/unrecoverable IDs: " + ChatColor.WHITE + counts[2]);
        player.sendMessage(ChatColor.GRAY + "Skipped non-air mismatches: " + ChatColor.WHITE + counts[3]);
        player.sendMessage(ChatColor.GRAY + "Recovery time: " + ChatColor.WHITE + (System.currentTimeMillis() - start) + "ms");

        if (counts[0] > 0) {
            player.sendMessage(ChatColor.YELLOW + "A normal server restart is recommended after recovery so Slimefun reloads tickers and menus cleanly.");
        }
    }

    private Selection requireSelection(Player player) {
        Selection selection = SelectionResolver.resolve(player);
        if (selection == null) {
            player.sendMessage(ChatColor.RED + "Select a region with WorldEdit/FAWE first (//pos1 and //pos2).");
            return null;
        }
        return selection;
    }

    private boolean isSelectionAllowed(Player player, Selection selection) {
        long maximum = WorldEditSlimefun.getInstance().getConfig().getLong("max-selection-blocks", 2_000_000L);
        if (maximum > 0 && selection.volume() > maximum) {
            player.sendMessage(ChatColor.RED + "Selection is too large for a synchronous Slimefun operation: "
                    + selection.volume() + " blocks (limit " + maximum + ").");
            player.sendMessage(ChatColor.GRAY + "Raise max-selection-blocks in plugins/WorldEditSlimefun/config.yml if needed.");
            return false;
        }
        return true;
    }

    /**
     * @param selection selection to iterate
     * @param blockRunnable operation to perform for every block
     * @return amount of blocks visited
     */
    private long loopThroughSelection(Selection selection, Consumer<Block> blockRunnable) {
        long amountOfBlocks = 0;
        for (int x = selection.minX(); x <= selection.maxX(); x++) {
            for (int z = selection.minZ(); z <= selection.maxZ(); z++) {
                for (int y = selection.minY(); y <= selection.maxY(); y++) {
                    blockRunnable.accept(selection.world().getBlockAt(x, y, z));
                    amountOfBlocks++;
                }
            }
        }
        return amountOfBlocks;
    }
}
