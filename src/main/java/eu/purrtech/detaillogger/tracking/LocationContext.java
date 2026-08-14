package eu.purrtech.detaillogger.tracking;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Resolved "where is this unit right now" - one of player inventory, ground, a block container
 * (chest/barrel/furnace/hopper/dispenser/placed shulker/...), an entity container (minecart
 * chest, minecart hopper...), an ender chest, a generic third-party chest-GUI menu, or nested
 * inside a shulker box that's itself being carried as an item. Maps 1:1 onto {@code locations}
 * table columns.
 */
public record LocationContext(
        String locationType,
        String playerUuid,
        Integer slot,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String entityUuid,
        String containerType,
        String parentShulkerUuid,
        String menuName
) {

    public static LocationContext playerInventory(Player player, int slot) {
        return new LocationContext("PLAYER_INVENTORY", player.getUniqueId().toString(), slot,
                null, null, null, null, null, null, null, null);
    }

    public static LocationContext enderChest(Player owner, int slot) {
        return new LocationContext("ENDER_CHEST", owner.getUniqueId().toString(), slot,
                null, null, null, null, null, null, null, null);
    }

    public static LocationContext ground(Location location) {
        return new LocationContext("GROUND", null, null,
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                null, null, null, null);
    }

    public static LocationContext blockContainer(Location location, String containerType, int slot) {
        return new LocationContext("BLOCK_CONTAINER", null, slot,
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                null, containerType, null, null);
    }

    public static LocationContext entityContainer(Entity entity, int slot) {
        return new LocationContext("ENTITY_CONTAINER", null, slot,
                null, null, null, null, entity.getUniqueId().toString(), entity.getType().name(), null, null);
    }

    /**
     * A tracked item currently sitting inside a shulker box that's itself being carried as an
     * item (not placed - a placed shulker is just a {@link #blockContainer}). The parent
     * shulker's own identity is a synthetic "container anchor" unit, not necessarily a
     * user-defined tracked template.
     */
    public static LocationContext nestedShulker(String parentShulkerUuid, int slot) {
        return new LocationContext("NESTED_SHULKER", null, slot,
                null, null, null, null, null, null, parentShulkerUuid, null);
    }

    /** A tracked BLOCK-kind unit sitting in the world (as opposed to an item inside a container). */
    public static LocationContext placedBlock(Location location, String material) {
        return new LocationContext("PLACED_BLOCK", null, null,
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                null, material, null, null);
    }

    /**
     * A tracked item placed into a custom (usually third-party) chest-style GUI menu - not a real
     * block/entity container, identified by its {@code InventoryHolder} class or view title.
     * Independent of DisplayGUI, which doesn't have real inventory slots at all (see
     * {@code ShulkerNestingListener}'s and this phase's own notes).
     */
    public static LocationContext menu(String menuName, int slot) {
        return new LocationContext("PLACED_INTO_MENU", null, slot,
                null, null, null, null, null, null, null, menuName);
    }
}
