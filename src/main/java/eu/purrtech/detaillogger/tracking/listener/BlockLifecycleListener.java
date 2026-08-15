package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.BlockTrackingService;
import eu.purrtech.detaillogger.tracking.pdc.TrackedEntityTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Block placement/breaking (the item&lt;-&gt;block bridge), piston push/pull reconciliation,
 * explosion destruction, and falling-block bridging so a tracked block that starts falling
 * doesn't look like destroy+genesis when it lands.
 * <p>
 * TileState blocks (chest/barrel/furnace/shulker) are piston-immovable in vanilla Minecraft, so
 * the piston handlers only ever meaningfully affect plain (BlockIdentityIndex-tracked) blocks -
 * {@link BlockTrackingService#moveBlock} is a safe no-op for anything else.
 */
public final class BlockLifecycleListener implements Listener {

    private final BlockTrackingService blockTracking;
    private final TrackedEntityTag entityTag;
    private final Plugin plugin;

    public BlockLifecycleListener(BlockTrackingService blockTracking, TrackedEntityTag entityTag, Plugin plugin) {
        this.blockTracking = blockTracking;
        this.entityTag = entityTag;
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        blockTracking.onBlockPlaced(event.getBlockPlaced(), event.getItemInHand(), event.getPlayer());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockTrackingService.TrackedBlock tracked = blockTracking.readTracked(block);
        if (tracked == null) {
            return;
        }
        event.setDropItems(false);
        ItemStack drop = blockTracking.onBlockBroken(block, tracked.uuid(), tracked.templateKey(), event.getPlayer());
        block.getWorld().dropItemNaturally(block.getLocation(), drop);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        movePushedBlocks(event.getBlocks(), event.getDirection());
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        movePushedBlocks(event.getBlocks(), event.getDirection());
    }

    private void movePushedBlocks(Iterable<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            BlockTrackingService.TrackedBlock tracked = blockTracking.readTracked(block);
            if (tracked == null) {
                continue;
            }
            Location from = block.getLocation();
            Location to = from.clone().add(direction.getModX(), direction.getModY(), direction.getModZ());
            blockTracking.moveBlock(tracked.uuid(), tracked.templateKey(), from, to);
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        destroyExploded(event.blockList());
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        destroyExploded(event.blockList());
    }

    private void destroyExploded(Iterable<Block> blocks) {
        for (Block block : blocks) {
            BlockTrackingService.TrackedBlock tracked = blockTracking.readTracked(block);
            if (tracked != null) {
                blockTracking.markDestroyed(tracked.uuid(), "EXPLOSION", block, null);
            }
        }
    }

    @EventHandler
    public void onFallingBlockSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        Block block = event.getLocation().getBlock();
        BlockTrackingService.TrackedBlock tracked = blockTracking.readTracked(block);
        if (tracked == null) {
            return;
        }
        // The block is about to disappear and become this entity - bridge identity onto it so
        // landing (below) can reconnect it instead of it looking like destroy+genesis.
        entityTag.write(fallingBlock, tracked.uuid(), tracked.templateKey());
        blockTracking.untrackForFall(block);
    }

    @EventHandler
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) {
            return;
        }
        UUID uuid = entityTag.readUuid(fallingBlock);
        if (uuid == null) {
            return;
        }
        String templateKey = entityTag.readTemplateKey(fallingBlock);
        entityTag.strip(fallingBlock);

        Block block = event.getBlock();
        // Fires before the block actually takes on its new material, so the BlockState (e.g. if
        // it becomes a TileState) doesn't exist to tag yet - reland on the next tick instead.
        Bukkit.getScheduler().runTask(plugin, () -> blockTracking.relandBlock(block, uuid, templateKey));
    }
}
