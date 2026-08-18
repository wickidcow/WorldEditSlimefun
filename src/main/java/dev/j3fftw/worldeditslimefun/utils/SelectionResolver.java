package dev.j3fftw.worldeditslimefun.utils;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import io.github.thebusybiscuit.slimefun4.libraries.dough.blocks.BlockPosition;
import org.bukkit.World;
import org.bukkit.entity.Player;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the player's active WorldEdit/FAWE selection first and falls back to
 * the legacy WorldEditSlimefun selection when no WorldEdit selection exists.
 */
public final class SelectionResolver {

    private SelectionResolver() {}

    @Nullable
    public static Selection resolve(@Nonnull Player player) {
        try {
            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager()
                    .get(BukkitAdapter.adapt(player));
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();

            return new Selection(
                    player.getWorld(),
                    min.x(),
                    min.y(),
                    min.z(),
                    max.x(),
                    max.y(),
                    max.z());
        } catch (IncompleteRegionException ignored) {
            // Fall back to the addon's original selection system.
        }

        BlockPosition pos1 = PositionManager.getPositionOne(player);
        BlockPosition pos2 = PositionManager.getPositionTwo(player);
        if (pos1 == null || pos2 == null || !pos1.getWorld().equals(pos2.getWorld())) {
            return null;
        }

        return new Selection(
                pos1.getWorld(),
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ()));
    }

    public record Selection(
            World world,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {

        public long volume() {
            return (long) (maxX - minX + 1)
                    * (long) (maxY - minY + 1)
                    * (long) (maxZ - minZ + 1);
        }
    }
}
