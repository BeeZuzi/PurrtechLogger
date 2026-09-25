package eu.purrtech.detaillogger.tracking;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures which OTHER online players were standing near an event's location at the moment it was
 * recorded, so the admin GUI's event detail page can show "kdo byl v okolí" (who was nearby) -
 * bystanders, not just the actor who is already recorded separately as the event's player_uuid.
 * Best-effort only: only players online right then and in the same world are visible to the
 * server at all, so this can never reflect anyone who was offline or in a different world.
 * <p>
 * Must be called on the main thread - reads live {@link Player} positions via the Bukkit API, same
 * as every existing {@code eventDao.enqueue(...)} call site already does (world/actor lookups).
 */
public final class NearbyPlayers {

    public static final double DEFAULT_RADIUS_BLOCKS = 50;

    private NearbyPlayers() {
    }

    /**
     * Comma-separated player names within {@link #DEFAULT_RADIUS_BLOCKS} of (world,x,y,z),
     * excluding {@code excludePlayerUuid} (the event's own actor, already shown separately) - or
     * null if the location is unknown or nobody else was in range.
     */
    public static String capture(String world, Integer x, Integer y, Integer z, String excludePlayerUuid) {
        return capture(world, x, y, z, excludePlayerUuid, DEFAULT_RADIUS_BLOCKS);
    }

    public static String capture(String world, Integer x, Integer y, Integer z, String excludePlayerUuid,
                                  double radiusBlocks) {
        if (world == null || x == null || y == null || z == null) {
            return null;
        }
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        double radiusSq = radiusBlocks * radiusBlocks;
        List<String> names = new ArrayList<>();
        for (Player p : bukkitWorld.getPlayers()) {
            if (excludePlayerUuid != null && p.getUniqueId().toString().equals(excludePlayerUuid)) {
                continue;
            }
            Location loc = p.getLocation();
            double dx = loc.getX() - x;
            double dy = loc.getY() - y;
            double dz = loc.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                names.add(p.getName());
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }
}
