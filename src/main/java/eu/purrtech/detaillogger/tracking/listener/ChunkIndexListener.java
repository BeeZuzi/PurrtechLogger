package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.db.dao.BlockLocationRecord;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import eu.purrtech.detaillogger.tracking.BlockIdentityIndex;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;

/**
 * Keeps {@link BlockIdentityIndex} mirrored to exactly the currently loaded chunks, so plain
 * (non-TileState) tracked-block identity never has to live in memory beyond that.
 */
public final class ChunkIndexListener implements Listener {

    private final BlockIdentityIndex index;
    private final LocationDao locationDao;
    private final Plugin plugin;

    public ChunkIndexListener(BlockIdentityIndex index, LocationDao locationDao, Plugin plugin) {
        this.index = index;
        this.locationDao = locationDao;
        this.plugin = plugin;
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent event) {
        String world = event.getWorld().getName();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<BlockLocationRecord> found = locationDao.findBlockLocationsInChunk(world, chunkX, chunkZ);
                if (found.isEmpty()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> index.loadChunk(world, chunkX, chunkZ, found));
            } catch (SQLException e) {
                plugin.getLogger().severe("Nacteni block indexu pro chunk [" + chunkX + "," + chunkZ + "] selhalo: " + e);
            }
        });
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent event) {
        index.unloadChunk(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
