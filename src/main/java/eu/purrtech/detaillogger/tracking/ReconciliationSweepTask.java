package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Periodic anti-dupe check: the DB is authoritative, PDC is only a fast local hint, so this
 * re-derives ground truth by scanning what's actually physically present (every online player's
 * inventory + ender chest) and cross-checks it against what the DB expects.
 * <p>
 * A tracked unit physically observed more than once at the same time is impossible unless
 * something duplicated it - creative mode is the one place that's allowed by design (it re-mints
 * a fresh identity for the extra copy and records the lineage instead of alerting), anything else
 * raises a {@code dupe_alerts} row. A unit observed at all despite the DB saying it's already
 * destroyed is flagged the same way.
 * <p>
 * Scope: online players' own inventories/ender chests only. Ground items, block containers and
 * tracked blocks aren't swept here - a real gap for a future enhancement, not attempted in this
 * phase (block identity already has its own DB-authoritative consistency story via
 * {@link BlockIdentityIndex}; item containers get lighter-touch coverage for free whenever a
 * player opens one, via {@link eu.purrtech.detaillogger.tracking.listener.ContainerListener}).
 */
public final class ReconciliationSweepTask {

    private final ItemTrackingService tracking;
    private final TrackedUnitDao trackedUnitDao;
    private final DupeAlertDao dupeAlertDao;
    private final TemplateRegistry registry;
    private final Plugin plugin;
    private final Logger logger;

    public ReconciliationSweepTask(ItemTrackingService tracking, TrackedUnitDao trackedUnitDao,
                                    DupeAlertDao dupeAlertDao, TemplateRegistry registry,
                                    Plugin plugin, Logger logger) {
        this.tracking = tracking;
        this.trackedUnitDao = trackedUnitDao;
        this.dupeAlertDao = dupeAlertDao;
        this.registry = registry;
        this.plugin = plugin;
        this.logger = logger;
    }

    /** Must be called on the main thread - it reads live Player/Inventory state. */
    public void runSweep() {
        List<Sighting> sightings = collectSightings();
        if (sightings.isEmpty()) {
            return;
        }

        List<String> uuidsToCheck = sightings.stream()
                .map(s -> s.uuid().toString())
                .distinct()
                .toList();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, Boolean> aliveByUuid = trackedUnitDao.findAliveStatus(uuidsToCheck);
                Bukkit.getScheduler().runTask(plugin, () -> applyFindings(sightings, aliveByUuid));
            } catch (SQLException e) {
                logger.severe("Reconciliation sweep selhal pri kontrole DB: " + e);
            }
        });
    }

    private List<Sighting> collectSightings() {
        List<Sighting> sightings = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            collectFrom(player, player.getInventory(), false, sightings);
            collectFrom(player, player.getEnderChest(), true, sightings);
        }
        return sightings;
    }

    private void collectFrom(Player player, Inventory inventory, boolean isEnderChest, List<Sighting> sightings) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            for (UUID uuid : tracking.readAllUnits(item)) {
                sightings.add(new Sighting(uuid, player, inventory, slot, item, isEnderChest));
            }
        }
    }

    /** Must run on the main thread - relabeling a creative duplicate mutates live inventories. */
    private void applyFindings(List<Sighting> sightings, Map<String, Boolean> aliveByUuid) {
        Map<UUID, List<Sighting>> byUuid = sightings.stream()
                .collect(Collectors.groupingBy(Sighting::uuid));
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, List<Sighting>> entry : byUuid.entrySet()) {
            UUID uuid = entry.getKey();
            List<Sighting> occurrences = entry.getValue();
            Boolean alive = aliveByUuid.get(uuid.toString());

            if (occurrences.size() > 1) {
                handleDuplicate(uuid, occurrences, now);
            } else if (Boolean.FALSE.equals(alive)) {
                raiseAlert(uuid, occurrences.get(0), 1, 0, now, "RESURRECTED_DESTROYED_UNIT");
            }
            // exactly one sighting + DB says alive (or unit unknown to DB, which shouldn't
            // happen for anything genuinely tagged by this plugin) - nothing to do.
        }
    }

    private void handleDuplicate(UUID uuid, List<Sighting> occurrences, long now) {
        // Keep the first sighting as the canonical original; every other physical occurrence of
        // the same identity needs a decision - creative players get a fresh identity (allowed by
        // design), anyone else is a genuine violation.
        for (int i = 1; i < occurrences.size(); i++) {
            Sighting extra = occurrences.get(i);
            if (extra.player().getGameMode() == GameMode.CREATIVE) {
                relabelAsCreativeDuplicate(uuid, extra);
            } else {
                raiseAlert(uuid, extra, occurrences.size(), 1, now, "DUPLICATE_UUID_SEEN_TWICE");
            }
        }
    }

    private void relabelAsCreativeDuplicate(UUID original, Sighting extra) {
        List<UUID> fresh = tracking.registerCreativeDuplicate(extra.item(), List.of(original));
        extra.inventory().setItem(extra.slot(), extra.item());
        if (!fresh.isEmpty()) {
            LocationContext ctx = extra.isEnderChest()
                    ? LocationContext.enderChest(extra.player(), extra.slot())
                    : LocationContext.playerInventory(extra.player(), extra.slot());
            tracking.recordLocationForAll(fresh, ctx, "CREATIVE_DUPLICATE", extra.player());
        }
    }

    private void raiseAlert(UUID uuid, Sighting sighting, int observedCount, int expectedAliveCount,
                             long now, String note) {
        Integer templateId = registry.idOf(tracking.readTemplateKey(sighting.item()));
        Location loc = sighting.player().getLocation();
        dupeAlertDao.enqueueAlert(templateId, uuid.toString(), now, expectedAliveCount, observedCount,
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                sighting.player().getUniqueId().toString(), "HIGH", note);
    }

    private record Sighting(UUID uuid, Player player, Inventory inventory, int slot, ItemStack item,
                             boolean isEnderChest) {
    }
}
