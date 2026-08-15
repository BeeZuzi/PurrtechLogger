package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.PlayerDirectoryService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps the {@code players} directory (every player who has ever joined, online/offline status,
 * nickname history) in sync. Separate from {@link PlayerJoinScanListener}, which only concerns
 * itself with scanning a joining player's inventory for tracked items.
 */
public final class PlayerPresenceListener implements Listener {

    private final PlayerDirectoryService directory;

    public PlayerPresenceListener(PlayerDirectoryService directory) {
        this.directory = directory;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        directory.recordJoin(player.getUniqueId().toString(), player.getName(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        directory.recordQuit(player.getUniqueId().toString(), System.currentTimeMillis());
    }
}
