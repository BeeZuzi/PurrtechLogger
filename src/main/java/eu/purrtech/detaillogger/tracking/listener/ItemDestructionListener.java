package eu.purrtech.detaillogger.tracking.listener;

import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ground-item destruction (lava, void, despawn, explosion) and creative mid-air delete. Causes
 * like PICKUP/UNLOAD/MERGE aren't destruction - the item still exists, it just left the world for
 * an unrelated reason - so only a narrow allowlist of {@link EntityRemoveEvent.Cause} is treated
 * as "destroyed" here.
 */
public final class ItemDestructionListener implements Listener {

    private static final Set<EntityRemoveEvent.Cause> DESTRUCTION_CAUSES = EnumSet.of(
            EntityRemoveEvent.Cause.DEATH,
            EntityRemoveEvent.Cause.DESPAWN,
            EntityRemoveEvent.Cause.EXPLODE,
            EntityRemoveEvent.Cause.OUT_OF_WORLD
    );

    private final ItemTrackingService tracking;

    // Short-lived, bounded by currently-damaged tracked ground items in the same tick - consulted
    // and removed immediately by onRemove, never grows unbounded.
    private final Map<UUID, String> pendingDamageCause = new ConcurrentHashMap<>();

    public ItemDestructionListener(ItemTrackingService tracking) {
        this.tracking = tracking;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (!tracking.isTracked(item.getItemStack())) {
            return;
        }
        pendingDamageCause.put(item.getUniqueId(), describe(event.getCause()));
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        String damageCause = pendingDamageCause.remove(item.getUniqueId());

        if (!DESTRUCTION_CAUSES.contains(event.getCause())) {
            return;
        }

        List<UUID> units = tracking.readAllUnits(item.getItemStack());
        if (units.isEmpty()) {
            return;
        }

        String cause = damageCause != null ? damageCause : event.getCause().name();
        tracking.markDestroyedForAll(units, cause, item.getLocation(), null);
    }

    /**
     * Creative mode clicking outside an inventory with an item on the cursor deletes it instead
     * of dropping it - no ground item ever spawns, so this is the only place to catch it.
     */
    @EventHandler
    public void onCreativeDelete(InventoryClickEvent event) {
        if (event.getClickedInventory() != null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        List<UUID> units = tracking.readAllUnits(cursor);
        if (units.isEmpty()) {
            return;
        }
        tracking.markDestroyedForAll(units, "CREATIVE_DELETE", player.getLocation(), player);
    }

    private static String describe(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case LAVA -> "LAVA";
            case FIRE, FIRE_TICK -> "FIRE";
            case VOID -> "VOID";
            case CONTACT -> "CACTUS";
            default -> cause.name();
        };
    }
}
