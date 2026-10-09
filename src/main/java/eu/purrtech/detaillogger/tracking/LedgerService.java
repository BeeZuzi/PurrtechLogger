package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.LedgerDao;
import eu.purrtech.detaillogger.template.TemplateDefinition;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Counts, instead of tags, the items of templates with {@code mode: ledger} (stackable commodities -
 * keys, money, diamonds), which therefore keep stacking like vanilla. Three checks:
 * <ol>
 *   <li><b>Books every change</b> of a player's count with its cause (pickup, drop, craft, give command,
 *       reported issue...). A change nothing explains - items that simply appeared - has to be reported
 *       by the plugin that created them via {@code /purrlog issue}; otherwise it is an alert.</li>
 *   <li><b>Conservation per inventory action</b>: a click/drag only moves items between the inventories
 *       it touches, so their total must not grow. If it does, items were created - a duplication
 *       glitch - unless a crate/shop reports it.</li>
 *   <li><b>Conservation per hopper/dropper move</b>: same check for source + destination.</li>
 * </ol>
 * Everything runs on the main thread. A count is the player's inventory only (not the ender chest).
 */
public final class LedgerService {

    private static final long CREDIT_TTL_MS = 10_000;
    /** Containers whose contents are produced by the container itself (recipes), so a total that grows is normal. */
    private static final Set<InventoryType> CONVERTING = EnumSet.of(InventoryType.WORKBENCH, InventoryType.CRAFTING,
            InventoryType.ANVIL, InventoryType.SMITHING, InventoryType.GRINDSTONE, InventoryType.STONECUTTER,
            InventoryType.LOOM, InventoryType.CARTOGRAPHY, InventoryType.MERCHANT, InventoryType.ENCHANTING,
            InventoryType.BREWING, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER,
            InventoryType.CRAFTER);
    private static final Set<String> GIVE_COMMANDS = Set.of("give", "i", "item", "kit", "kits");

    private final ItemTrackingService tracking;
    private final TemplateRegistry registry;
    private final LedgerDao ledgerDao;
    private final DupeAlertDao dupeAlertDao;
    private final Plugin plugin;
    private final IssueMatcher matcher = new IssueMatcher();

    private record Net(int tick, int net) {
    }

    private record Stamp(int tick, Set<String> causes) {
    }

    private final Map<IssueMatcher.Key, Net> tickNet = new LinkedHashMap<>();
    private final Map<UUID, Stamp> stamps = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> counts = new HashMap<>();
    private boolean flushScheduled;
    private int commandTick = -1;

    public LedgerService(ItemTrackingService tracking, TemplateRegistry registry, LedgerDao ledgerDao,
                         DupeAlertDao dupeAlertDao, Plugin plugin) {
        this.tracking = tracking;
        this.registry = registry;
        this.ledgerDao = ledgerDao;
        this.dupeAlertDao = dupeAlertDao;
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
    }

    // ---- causes -----------------------------------------------------------------------------

    /** Remembers that something ordinary (click, pickup, ...) is happening to this player this tick. */
    public void mark(UUID player, String cause) {
        int tick = Bukkit.getCurrentTick();
        Stamp stamp = stamps.get(player);
        if (stamp == null || stamp.tick() != tick) {
            stamp = new Stamp(tick, new HashSet<>());
            stamps.put(player, stamp);
        }
        stamp.causes().add(cause);
    }

    /** A give-like command ran this tick (console or player) - items may appear without any other cause. */
    public void commandRan(String commandLine) {
        String name = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        name = name.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (GIVE_COMMANDS.contains(name)) {
            commandTick = Bukkit.getCurrentTick();
        }
    }

    private String explain(UUID player, int tick) {
        Stamp stamp = stamps.get(player);
        Set<String> causes = stamp != null && stamp.tick() == tick ? stamp.causes() : Set.of();
        // CREATIVE first: a creative-menu click is also a plain click, but it is the one that matters.
        for (String cause : List.of("CREATIVE", "CRAFT", "PICKUP", "DROP", "CLOSE", "CLICK", "SWAP", "INTERACT")) {
            if (causes.contains(cause)) {
                return cause;
            }
        }
        return commandTick == tick ? "COMMAND" : null;
    }

    // ---- 1. books every change --------------------------------------------------------------

    private String keyOf(ItemStack item) {
        return tracking.ledgerTemplate(item).map(TemplateDefinition::key).orElse(null);
    }

    /** A slot of the player's inventory changed from {@code old} to {@code now}. */
    public void slotChanged(Player player, ItemStack old, ItemStack now) {
        String oldKey = keyOf(old);
        String newKey = keyOf(now);
        if (oldKey == null && newKey == null) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (oldKey != null) {
            add(player.getUniqueId(), oldKey, tick, -old.getAmount());
        }
        if (newKey != null) {
            add(player.getUniqueId(), newKey, tick, now.getAmount());
        }
    }

    private void add(UUID player, String key, int tick, int delta) {
        tickNet.merge(new IssueMatcher.Key(player, key), new Net(tick, delta),
                (a, b) -> new Net(a.tick(), a.net() + b.net()));
        if (!flushScheduled) {
            flushScheduled = true;
            Bukkit.getScheduler().runTask(plugin, this::flush);
        }
    }

    private void flush() {
        flushScheduled = false;
        Map<IssueMatcher.Key, Net> batch = new LinkedHashMap<>(tickNet);
        tickNet.clear();
        long now = System.currentTimeMillis();
        for (Map.Entry<IssueMatcher.Key, Net> entry : batch.entrySet()) {
            IssueMatcher.Key key = entry.getKey();
            int net = entry.getValue().net();
            if (net == 0) {
                continue; // items only shuffled between slots
            }
            int total = counts.computeIfAbsent(key.player(), k -> new HashMap<>()).merge(key.template(), net, Integer::sum);
            String cause = explain(key.player(), entry.getValue().tick());
            if (net > 0 && cause == null) {
                // Appeared from nowhere: a reporting plugin may still tell us (or already did).
                var matched = matcher.addGain(key, net, "bez priciny", now, graceMillis());
                if (matched.amount() > 0) {
                    book(key, matched.amount(), "ISSUED", total, matched.text(), now);
                }
            } else if (cause != null && !cause.equals("CLICK")) {
                book(key, net, cause, total, null, now); // clicks only shuffle - not booked, see conservation below
            } else if (cause == null) {
                book(key, net, "OUT", total, null, now);
            }
        }
    }

    private void book(IssueMatcher.Key key, int delta, String cause, int total, String detail, long now) {
        ledgerDao.enqueue(now, key.player().toString(), key.template(), delta, cause, total, detail);
    }

    // ---- /purrlog issue ---------------------------------------------------------------------

    /** A plugin reports it handed {@code amount} of {@code template} to {@code player}. */
    public String issue(Player player, String template, int amount, String reason) {
        long now = System.currentTimeMillis();
        var key = new IssueMatcher.Key(player.getUniqueId(), template);
        var matched = matcher.addCredit(key, amount, reason, now, CREDIT_TTL_MS);
        if (matched.amount() > 0) {
            int total = counts.computeIfAbsent(key.player(), k -> new HashMap<>()).getOrDefault(template, 0);
            book(key, matched.amount(), "ISSUED", total, reason, now);
        }
        return matched.amount() == amount
                ? "zapsano (" + amount + " ks, item uz dorazil)"
                : "zapsano (" + amount + " ks, ceka na item az " + CREDIT_TTL_MS / 1000 + " s)";
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (IssueMatcher.Unexplained gone : matcher.expire(now)) {
            Player player = Bukkit.getPlayer(gone.key().player());
            int total = counts.getOrDefault(gone.key().player(), Map.of()).getOrDefault(gone.key().template(), 0);
            book(gone.key(), gone.amount(), "UNEXPLAINED", total, gone.note(), now);
            if (player != null) {
                alert(gone.key().template(), gone.amount(), player.getUniqueId().toString(), player.getLocation(),
                        "LEDGER_UNEXPLAINED_GAIN " + gone.key().template() + " +" + gone.amount() + " (" + gone.note() + ")");
            }
        }
    }

    private long graceMillis() {
        return Math.max(1, plugin.getConfig().getInt("ledger.grace-seconds", 5)) * 1000L;
    }

    private void alert(String template, int amount, String playerUuid, Location where, String note) {
        if (!plugin.getConfig().getBoolean("ledger.alerts", true) || amount < plugin.getConfig().getInt("ledger.alert-threshold", 1)) {
            return;
        }
        dupeAlertDao.enqueueAlert(registry.idOf(template), null, System.currentTimeMillis(), 0, amount,
                where != null && where.getWorld() != null ? where.getWorld().getName() : null,
                where != null ? where.getBlockX() : null, where != null ? where.getBlockY() : null,
                where != null ? where.getBlockZ() : null, playerUuid, "HIGH", note);
    }

    // ---- 2. conservation per click/drag -----------------------------------------------------

    /** Sum of one ledger item across the inventories an action touches. */
    private int sum(String key, Inventory... inventories) {
        int total = 0;
        for (Inventory inventory : inventories) {
            for (ItemStack item : inventory.getContents()) {
                if (item != null && key.equals(keyOf(item))) {
                    total += item.getAmount();
                }
            }
        }
        return total;
    }

    /**
     * Called as a click/drag begins. Ledger items among {@code involved} are counted now and again next
     * tick; a bigger total afterwards means the action created items.
     */
    public void watchTransaction(Player player, InventoryView view, List<ItemStack> involved) {
        if (player.getGameMode() == GameMode.CREATIVE || CONVERTING.contains(view.getTopInventory().getType())) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (ItemStack item : involved) {
            String key = keyOf(item);
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty() || hopperAdjacent(view.getTopInventory())) {
            return; // a hopper may legitimately feed the container in the same tick
        }
        Map<String, Integer> before = new HashMap<>();
        for (String key : keys) {
            before.put(key, sum(key, view.getTopInventory(), view.getBottomInventory()) + cursorAmount(player, key));
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String key : keys) {
                int after = sum(key, view.getTopInventory(), view.getBottomInventory()) + cursorAmount(player, key);
                int created = after - before.get(key);
                if (created > 0) {
                    // May be a crate/shop giving through its GUI - give it the chance to report.
                    var gain = new IssueMatcher.Key(player.getUniqueId(), key);
                    var matched = matcher.addGain(gain, created, "vznik pri kliknuti v " + view.getTopInventory().getType(),
                            System.currentTimeMillis(), graceMillis());
                    if (matched.amount() > 0) {
                        book(gain, matched.amount(), "ISSUED", 0, matched.text(), System.currentTimeMillis());
                    }
                }
            }
        });
    }

    private int cursorAmount(Player player, String key) {
        ItemStack cursor = player.getItemOnCursor();
        return key.equals(keyOf(cursor)) ? cursor.getAmount() : 0;
    }

    private static boolean hopperAdjacent(Inventory inventory) {
        Location location = inventory.getLocation();
        if (location == null) {
            return false;
        }
        Block block = location.getBlock();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            Material type = block.getRelative(face).getType();
            if (type == Material.HOPPER || type == Material.DROPPER || type == Material.DISPENSER) {
                return true;
            }
        }
        return false;
    }

    // ---- 3. conservation per hopper/dropper move --------------------------------------------

    private final Map<Inventory, Set<Inventory>> sourcesThisTick = new HashMap<>();
    private int sourcesTick = -1;

    /** A hopper/dropper/hopper minecart is about to move {@code moved} from {@code source} into {@code destination}. */
    public void watchTransfer(Inventory source, Inventory destination, ItemStack moved) {
        String key = keyOf(moved);
        if (key == null) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (sourcesTick != tick) {
            sourcesThisTick.clear();
            sourcesTick = tick;
        }
        Set<Inventory> sources = sourcesThisTick.computeIfAbsent(destination, d -> new HashSet<>());
        boolean first = sources.isEmpty();
        sources.add(source);
        if (!first) {
            return; // several feeders in one tick make the total ambiguous - the first one carries the check
        }
        int before = sum(key, source, destination);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sourcesThisTick.getOrDefault(destination, Set.of()).size() > 1) {
                return; // another feeder joined in the meantime
            }
            int created = sum(key, source, destination) - before;
            if (created > 0) {
                Location where = destination.getLocation();
                alert(key, created, null, where, "LEDGER_DUPE_SUSPECT_TRANSFER " + key + " +" + created + " ("
                        + source.getType() + " -> " + destination.getType() + ")");
            }
        });
    }

    // ---- counts -----------------------------------------------------------------------------

    /** Counts what the player already carries, so later changes add up to a true total. */
    public void initCounts(Player player) {
        Map<String, Integer> mine = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            String key = keyOf(item);
            if (key != null) {
                mine.merge(key, item.getAmount(), Integer::sum);
            }
        }
        counts.put(player.getUniqueId(), mine);
    }

    public void forget(UUID player) {
        counts.remove(player);
        stamps.remove(player);
    }

    /** Online players holding the item, biggest first: {@code name -> count}. */
    public List<Map.Entry<String, Integer>> topHolders(String template) {
        List<Map.Entry<String, Integer>> holders = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            int count = counts.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(template, 0);
            if (count > 0) {
                holders.add(Map.entry(player.getName(), count));
            }
        }
        holders.sort(Comparator.comparing((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        return holders;
    }

    /** Whether {@code template} names a template in ledger mode. */
    public boolean isLedgerTemplate(String template) {
        return registry.definitions().stream().anyMatch(d -> d.ledger() && d.key().equals(template));
    }

    public List<String> ledgerTemplateKeys() {
        return registry.definitions().stream().filter(TemplateDefinition::ledger).map(TemplateDefinition::key).toList();
    }
}
