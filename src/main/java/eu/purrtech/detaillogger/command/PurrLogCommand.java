package eu.purrtech.detaillogger.command;

import eu.purrtech.detaillogger.DetailLoggerPlugin;
import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.PlayerRecord;
import eu.purrtech.detaillogger.db.WriteQueue;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;
import eu.purrtech.detaillogger.gui.AdminGuiService;
import eu.purrtech.detaillogger.lang.Lang;
import eu.purrtech.detaillogger.template.TemplateDefinition;
import eu.purrtech.detaillogger.template.TemplateItems;
import eu.purrtech.detaillogger.template.TemplateMatcher;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import eu.purrtech.detaillogger.tracking.HistoryService;
import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.LedgerService;
import eu.purrtech.detaillogger.tracking.StackDebug;
import eu.purrtech.detaillogger.tracking.listener.InventoryTagListener;
import eu.purrtech.detaillogger.tracking.PlayerDirectoryService;
import eu.purrtech.detaillogger.tracking.ReconciliationSweepTask;
import eu.purrtech.detaillogger.util.EventLineFormatter;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin/debug command tree for PurrTechDetailLogger. {@code /purrlog gui} opens the DisplayGUI
 * admin viewer when DisplayGUI is installed (see {@link AdminGuiService}); the text subcommands
 * below work either way and remain the only option when it isn't.
 * <p>
 * Implements Paper's {@link BasicCommand} rather than the legacy {@code CommandExecutor} -
 * paper-plugin.yml doesn't support YAML-based {@code commands:} declarations, so
 * {@code JavaPlugin#getCommand()} throws during {@code onEnable()}; commands must be registered
 * via {@code JavaPlugin#registerCommand}. Gated by {@link #ADMIN_PERMISSION} via
 * {@link #permission()} - Paper's own dispatch checks {@link #canUse} before {@link #execute}, so
 * no manual permission check is needed inside each subcommand.
 */
public final class PurrLogCommand implements BasicCommand {

    public static final String ADMIN_PERMISSION = "purrtechdetaillogger.admin";

    /** The most one /purrlog give hands out at once: a full inventory (36 slots of 64). */
    private static final int MAX_GIVE_AMOUNT = 36 * 64;

    private static final List<String> SUBCOMMANDS = List.of("gui", "history", "alerts", "players", "player", "sweep",
            "tracking", "mint", "give", "issue", "ledger", "reference", "reload", "template", "debug", "dbtest");

    private static final List<String> PLAYERS_LOGGER_ACCESS = List.of("_KingOfLords_", "Bee_zuzi", "realGAGMAN", "darLP");

    private final DetailLoggerPlugin plugin;
    private final TrackedUnitDao trackedUnitDao;
    private final EventDao eventDao;
    private final TemplateRegistry templateRegistry;
    private final File templatesFile;
    private final HistoryService historyService;
    private final DupeAlertDao dupeAlertDao;
    private final ReconciliationSweepTask sweepTask;
    private final AdminGuiService adminGuiService;
    private final PlayerDirectoryService playerDirectory;
    private final InventoryTagListener inventoryTagger;
    private final LedgerService ledgerService;
    private final ItemTrackingService itemTracking;

    public PurrLogCommand(DetailLoggerPlugin plugin, TrackedUnitDao trackedUnitDao, EventDao eventDao,
                           TemplateRegistry templateRegistry, File templatesFile, HistoryService historyService,
                           DupeAlertDao dupeAlertDao, ReconciliationSweepTask sweepTask,
                           AdminGuiService adminGuiService, PlayerDirectoryService playerDirectory,
                           InventoryTagListener inventoryTagger, LedgerService ledgerService,
                           ItemTrackingService itemTracking) {
        this.itemTracking = itemTracking;
        this.ledgerService = ledgerService;
        this.inventoryTagger = inventoryTagger;
        this.plugin = plugin;
        this.trackedUnitDao = trackedUnitDao;
        this.eventDao = eventDao;
        this.templateRegistry = templateRegistry;
        this.templatesFile = templatesFile;
        this.historyService = historyService;
        this.dupeAlertDao = dupeAlertDao;
        this.sweepTask = sweepTask;
        this.adminGuiService = adminGuiService;
        this.playerDirectory = playerDirectory;
    }

    @Override
    public @Nullable String permission() {
        return ADMIN_PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack commandSourceStack, String[] args) {
        CommandSender sender = commandSourceStack.getSender();


        if (!PLAYERS_LOGGER_ACCESS.contains(sender.getName())) {
            if (!(sender instanceof ConsoleCommandSender)) return;
        }


        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            runReload(sender);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("template") && args[1].equalsIgnoreCase("import")) {
            runTemplateImport(sender, args[2]);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("template") && args[1].equalsIgnoreCase("item")) {
            runTemplateItem(sender, args[2]);
            return;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            runGive(sender, args);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            runHistory(sender, args[1]);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("alerts")) {
            runAlerts(sender);
            return;
        }
        if (args.length >= 4 && args[0].equalsIgnoreCase("issue")) {
            runIssue(sender, args);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("tracking")) {
            runTracking(sender, args);
            return;
        }
        if (!plugin.isTrackingEnabled() && args.length >= 1
                && (args[0].equalsIgnoreCase("reference") || args[0].equalsIgnoreCase("mint"))) {
            sender.sendMessage(Lang.t("cmd.tracking-off-first"));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reference")) {
            runReference(sender, args);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("ledger")) {
            runLedger(sender, args);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mint")) {
            runMint(sender, args[1]);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("sweep")) {
            sweepTask.runSweep();
            sender.sendMessage(Lang.t("cmd.sweep-started"));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            runGui(sender);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            runDebug(sender, args);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("dbtest")) {
            runDbTest(sender);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("players")) {
            runPlayers(sender);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            runPlayer(sender, args[1]);
            return;
        }
        sendHelp(sender);
    }

    /**
     * {@code /purrlog issue <hrac> <sablona> <pocet> [duvod...]}: how a third-party plugin (crate, shop,
     * quest...) reports that it just gave a player ledger items. Run it right before or after the give -
     * the order does not matter (see IssueMatcher). Without a report such items are an alert.
     */
    private void runIssue(CommandSender sender, String[] args) {
        org.bukkit.entity.Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Lang.t("cmd.player-offline", args[1]));
            return;
        }
        if (!ledgerService.isLedgerTemplate(args[2])) {
            sender.sendMessage(Lang.t("cmd.issue.not-ledger", args[2], String.join(", ", ledgerService.ledgerTemplateKeys())));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0) {
            sender.sendMessage(Lang.t("cmd.issue.bad-amount"));
            return;
        }
        String reason = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : sender.getName();
        sender.sendMessage(ledgerService.issue(target, args[2], amount, reason));
    }

    /**
     * {@code /purrlog reference} - makes the held item the REFERENCE (master) of an item a crate hands
     * out; {@code /purrlog reference done} ends the setup. While the setup is open the master is left
     * alone, so it can be put into the crate's config. Afterwards every copy that shows up anywhere gets
     * its own fresh UUIDs, linked to the master.
     */
    private void runReference(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("done")) {
            itemTracking.closeReferenceSessions();
            sender.sendMessage(Lang.t("cmd.reference.done"));
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(Lang.t("cmd.reference.players-only"));
            return;
        }
        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        var reference = itemTracking.createReference(held, player.getLocation(), player);
        if (reference.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.reference.hold-one"));
            return;
        }
        player.getInventory().setItemInMainHand(held);
        sender.sendMessage(Lang.t("cmd.reference.created", reference.get()));
    }

    /** {@code /purrlog ledger [sablona]}: who holds how much of a ledger item, or the list of ledger items. */
    private void runLedger(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Lang.t("cmd.ledger.list", String.join(", ", ledgerService.ledgerTemplateKeys())));
            return;
        }
        if (!ledgerService.isLedgerTemplate(args[1])) {
            sender.sendMessage(Lang.t("cmd.ledger.not-ledger", args[1]));
            return;
        }
        var holders = ledgerService.topHolders(args[1]);
        sender.sendMessage(Lang.t("cmd.ledger.header", args[1]));
        if (holders.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.ledger.nobody"));
        }
        holders.stream().limit(15).forEach(e -> sender.sendMessage(" " + e.getKey() + ": " + e.getValue()));
    }

    /**
     * {@code /purrlog mint <hrac>}: tag every untagged template item in a player's inventory right now.
     * Items are normally tagged on their own the moment they appear (see {@link InventoryTagListener}),
     * so this is for scripts that want a guaranteed point ("give, then mint") and for inventories that
     * already hold untagged items. Callable from the console, e.g. as a crate's after-give command.
     */
    private void runMint(CommandSender sender, String playerName) {
        org.bukkit.entity.Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Lang.t("cmd.player-offline", playerName));
            return;
        }
        int created = inventoryTagger.tagInventory(target);
        sender.sendMessage(Lang.t("cmd.mint.done", target.getName(), created));
    }

    /** {@code /purrlog debug stats}: is the DB write queue the bottleneck, or the disk? */
    private void sendQueueStats(CommandSender sender) {
        WriteQueue.Stats s = plugin.writeQueueStats();
        double seconds = Math.max(1, s.uptimeMillis() / 1000.0);
        double busySeconds = Math.max(0.001, s.writeMillisTotal() / 1000.0);
        sender.sendMessage(Lang.t("cmd.queue.header"));
        sender.sendMessage(Lang.t("cmd.queue.pending", s.pending(), s.bulkPending(), s.bulkCapacity(),
                100L * s.bulkPending() / Math.max(1, s.bulkCapacity())));
        sender.sendMessage(Lang.t("cmd.queue.peak", s.peakPending()));
        sender.sendMessage(Lang.t("cmd.queue.dropped", s.droppedTotal()));
        sender.sendMessage(Lang.t("cmd.queue.written", s.writtenTotal(), Math.round(s.writtenTotal() / seconds)));
        sender.sendMessage(Lang.t("cmd.queue.speed", Math.round(s.writtenTotal() / busySeconds), s.lastBatchTasks(), s.lastBatchMillis()));
        sender.sendMessage(Lang.t("cmd.queue.busy", Math.round(100 * busySeconds / seconds)));
    }

    /** {@code /purrlog tracking off|on|status} - kill switch, see {@link DetailLoggerPlugin#setTrackingEnabled}. */
    private void runTracking(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "off" -> {
                if (!plugin.isTrackingEnabled()) {
                    sender.sendMessage(Lang.t("cmd.tracking.already-off"));
                } else {
                    plugin.setTrackingEnabled(false, sender);
                }
            }
            case "on" -> {
                if (plugin.isTrackingEnabled()) {
                    sender.sendMessage(Lang.t("cmd.tracking.already-on"));
                } else {
                    plugin.setTrackingEnabled(true, sender);
                }
            }
            default -> sender.sendMessage(Lang.t("cmd.tracking.status",
                    Lang.t(plugin.isTrackingEnabled() ? "common.on" : "common.off")));
        }
    }

    /** {@code /purrlog debug on|off|dump|stats|clear} - see {@link StackDebug}. */
    private void runDebug(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "on" -> {
                StackDebug.setEnabled(true);
                sender.sendMessage(Lang.t("cmd.debug.on", StackDebug.file().getPath()));
            }
            case "off" -> {
                StackDebug.setEnabled(false);
                sender.sendMessage(Lang.t("cmd.debug.off", StackDebug.file().getPath()));
            }
            case "dump" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(Lang.t("cmd.players-only"));
                    return;
                }
                boolean was = StackDebug.isEnabled();
                StackDebug.setEnabled(true);
                StackDebug.dumpView("DUMP " + player.getName(), player.getOpenInventory());
                StackDebug.setEnabled(was);
                sender.sendMessage(Lang.t("cmd.debug.dumped"));
            }
            case "stats" -> sendQueueStats(sender);
            case "clear" -> {
                boolean cleared = StackDebug.file().delete() || !StackDebug.file().exists();
                sender.sendMessage(Lang.t(cleared ? "cmd.debug.cleared" : "cmd.debug.clear-failed"));
            }
            default -> sender.sendMessage(Lang.t("cmd.debug.status",
                    Lang.t(StackDebug.isEnabled() ? "common.on" : "common.off")));
        }
    }

    private void sendHelp(CommandSender sender) {
        Lang.list("cmd.help").forEach(sender::sendMessage);
    }

    private void runPlayers(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerRecord> players = playerDirectory.listPlayers();
                Bukkit.getScheduler().runTask(plugin, () -> reportPlayers(sender, players));
            } catch (Exception e) {
                plugin.getLogger().severe("players lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void reportPlayers(CommandSender sender, List<PlayerRecord> players) {
        if (players.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.players.none"));
            return;
        }
        int shown = Math.min(players.size(), 30);
        sender.sendMessage(Lang.t("cmd.players.header", shown, players.size()));
        for (PlayerRecord p : players.subList(0, shown)) {
            sender.sendMessage(Lang.t("cmd.players.line", Lang.t(p.online() ? "common.online" : "common.offline"),
                    p.currentName(), p.uuid()));
        }
    }

    private void runPlayer(CommandSender sender, String query) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerRecord> matches = playerDirectory.searchPlayers(query);
                Bukkit.getScheduler().runTask(plugin, () -> reportPlayer(sender, query, matches));
            } catch (Exception e) {
                plugin.getLogger().severe("player lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void reportPlayer(CommandSender sender, String query, List<PlayerRecord> matches) {
        if (matches.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.player.none", query));
            return;
        }
        for (PlayerRecord p : matches) {
            sender.sendMessage("=== " + p.currentName() + " ===");
            sender.sendMessage("UUID: " + p.uuid());
            sender.sendMessage(Lang.t("cmd.player.state", Lang.t(p.online() ? "common.online" : "common.offline"),
                    formatTime(p.firstJoinedAt())));
        }
    }

    private void runGui(CommandSender sender) {
        if (adminGuiService == null) {
            sender.sendMessage(Lang.t("cmd.gui.no-displaygui"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.t("cmd.players-only"));
            return;
        }
        adminGuiService.openLastPageOrMain(player);
    }

    private void runAlerts(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<DupeAlertRecord> alerts = dupeAlertDao.findUnresolved(20);
                Bukkit.getScheduler().runTask(plugin, () -> reportAlerts(sender, alerts));
            } catch (Exception e) {
                plugin.getLogger().severe("alerts lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void reportAlerts(CommandSender sender, List<DupeAlertRecord> alerts) {
        if (alerts.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.alerts.none"));
            return;
        }
        sender.sendMessage(Lang.t("cmd.alerts.header", alerts.size()));
        for (DupeAlertRecord a : alerts) {
            String where = a.world() != null ? " @ " + a.world() + " " + a.x() + "," + a.y() + "," + a.z() : "";
            sender.sendMessage(Lang.t("cmd.alerts.line", a.severity(), a.note(), a.unitUuid(), a.observedCount(),
                    a.expectedAliveCount(), a.playerUuid(), where, formatTime(a.detectedAt())));
        }
    }

    private void runHistory(CommandSender sender, String uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<HistoryService.UnitHistory> found = historyService.lookup(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> reportHistory(sender, uuid, found));
            } catch (Exception e) {
                plugin.getLogger().severe("history lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void reportHistory(CommandSender sender, String uuid, Optional<HistoryService.UnitHistory> found) {
        if (found.isEmpty()) {
            sender.sendMessage(Lang.t("common.unknown-item", uuid));
            return;
        }
        TrackedUnitRecord unit = found.get().unit();
        sender.sendMessage("=== " + unit.uuid() + " ===");
        sender.sendMessage(Lang.t("cmd.history.unit", unit.templateId(), unit.kind(), unit.origin(), unit.alive()));
        if (!unit.alive()) {
            sender.sendMessage(Lang.t("cmd.history.destroyed", unit.destroyedCause(), formatTime(unit.destroyedAt())));
        }
        List<EventRecord> events = found.get().events();
        sender.sendMessage(Lang.t("cmd.history.header", events.size()));
        for (EventRecord e : events) {
            sender.sendMessage(" - " + EventLineFormatter.formatLine(e));
        }
    }

    private static String formatTime(Long epochMillis) {
        return EventLineFormatter.formatTime(epochMillis);
    }

    private void runReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.applyWriteQueueConfig();
        Lang.load(plugin);
        sender.sendMessage(Lang.t("cmd.reload.loading"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                templateRegistry.reloadBlocking();
                int loaded = templateRegistry.definitions().size();
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Lang.t("cmd.reload.done", loaded)));
            } catch (Exception e) {
                plugin.getLogger().severe("Reload sablon selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Lang.t("cmd.reload.failed")));
            }
        });
    }

    private void runTemplateImport(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.t("cmd.template.players-only"));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(Lang.t("cmd.template.empty-hand"));
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(templatesFile);
        String path = "templates." + key;
        if (yaml.contains(path)) {
            sender.sendMessage(Lang.t("cmd.template.exists", key));
            return;
        }

        yaml.set(path + ".material", held.getType().name());
        ItemMeta meta = held.getItemMeta();
        if (meta != null && meta.hasCustomModelDataComponent()) {
            List<Float> floats = meta.getCustomModelDataComponent().getFloats();
            if (floats.size() == 1) {
                yaml.set(path + ".custom-model-data", Math.round(floats.get(0)));
            }
        }
        yaml.set(path + ".item", TemplateItems.encode(itemTracking.untaggedSample(held)));
        yaml.set(path + ".track-items", true);
        yaml.set(path + ".track-blocks", false);

        try {
            yaml.save(templatesFile);
        } catch (IOException e) {
            sender.sendMessage(Lang.t("cmd.template.save-failed", e.getMessage()));
            return;
        }

        sender.sendMessage(Lang.t("cmd.template.added", key, held.getType()));
        runReload(sender);
    }

    /**
     * {@code /purrlog template item <key>}: the held item becomes the item {@code /purrlog give} hands
     * out for an EXISTING template - for templates made before the item was stored, or to swap it.
     * Only the stored item changes, not what the template matches on.
     */
    private void runTemplateItem(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.t("cmd.template.players-only"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(Lang.t("cmd.template.empty-hand"));
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(templatesFile);
        String path = "templates." + key;
        if (!yaml.contains(path)) {
            sender.sendMessage(Lang.t("cmd.template.unknown", key));
            return;
        }
        yaml.set(path + ".item", TemplateItems.encode(itemTracking.untaggedSample(held)));
        try {
            yaml.save(templatesFile);
        } catch (IOException e) {
            sender.sendMessage(Lang.t("cmd.template.save-failed", e.getMessage()));
            return;
        }
        sender.sendMessage(Lang.t("cmd.template.item-set", key, held.getType()));
        runReload(sender);
    }

    /**
     * {@code /purrlog give <player> <template> [amount]}: hands out the template's item - the very item
     * it was made from when it has one stored (see {@link TemplateItems}). The items are tagged the
     * moment they land in the inventory like any other template item; a {@code mode: ledger} template is
     * reported to the ledger as an issue, so the hand-out is not an unexplained gain.
     */
    private void runGive(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Lang.t("cmd.player-offline", args[1]));
            return;
        }
        Optional<TemplateDefinition> found = templateRegistry.definition(args[2]);
        if (found.isEmpty()) {
            sender.sendMessage(Lang.t("cmd.give.unknown-template", args[2], templateKeys()));
            return;
        }
        TemplateDefinition definition = found.get();

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                amount = 0;
            }
        }
        if (amount <= 0 || amount > MAX_GIVE_AMOUNT) {
            sender.sendMessage(Lang.t("cmd.give.bad-amount", MAX_GIVE_AMOUNT));
            return;
        }

        TemplateItems.Built built = TemplateItems.build(definition, plugin.getLogger());
        ItemStack unit = built.item();
        Optional<TemplateDefinition> matched = new TemplateMatcher(templateRegistry).match(unit);
        if (matched.isEmpty() || !matched.get().key().equals(definition.key())) {
            sender.sendMessage(Lang.t("cmd.give.mismatch", definition.key()));
        }

        if (definition.ledger()) {
            ledgerService.issue(target, definition.key(), amount, "give " + sender.getName());
        }

        int perStack = Math.max(1, unit.getMaxStackSize());
        int dropped = 0;
        for (int left = amount; left > 0; left -= perStack) {
            ItemStack stack = unit.clone();
            stack.setAmount(Math.min(perStack, left));
            for (ItemStack rest : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItem(target.getLocation(), rest);
                dropped += rest.getAmount();
            }
        }

        sender.sendMessage(Lang.t("cmd.give.done", amount, definition.key(), target.getName()));
        if (dropped > 0) {
            sender.sendMessage(Lang.t("cmd.give.dropped", dropped));
        }
        if (!built.exact()) {
            sender.sendMessage(Lang.t("cmd.give.approximate", definition.key()));
        }
    }

    private String templateKeys() {
        return String.join(", ", templateRegistry.definitions().stream().map(TemplateDefinition::key).toList());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        List<String> templateKeys = templateRegistry.definitions().stream().map(TemplateDefinition::key).toList();
        switch (sub) {
            case "give" -> {
                if (args.length == 2) {
                    return filter(onlineNames(), last);
                }
                if (args.length == 3) {
                    return filter(templateKeys, last);
                }
                return args.length == 4 ? filter(List.of("1", "16", "64"), last) : List.of();
            }
            case "mint", "issue" -> {
                return args.length == 2 ? filter(onlineNames(), last) : List.of();
            }
            case "template" -> {
                if (args.length == 2) {
                    return filter(List.of("import", "item"), last);
                }
                return args.length == 3 && args[1].equalsIgnoreCase("item") ? filter(templateKeys, last) : List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private void runDbTest(CommandSender sender) {
        String uuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        // template_id 0 is the inert bootstrap fixture seeded by V1__init.sql for exactly this test.
        trackedUnitDao.enqueueUpsert(uuid, 0, "ITEM", "DBTEST", null, now, true, null, null);
        eventDao.enqueue(uuid, "DBTEST_PING", now, "world", 0, 64, 0, null, null);

        sender.sendMessage(Lang.t("cmd.dbtest.written", uuid));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                TrackedUnitRecord unit = trackedUnitDao.findByUuid(uuid);
                List<EventRecord> events = eventDao.findByUnit(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(unit != null ? Lang.t("cmd.dbtest.unit-ok", unit.origin(), unit.alive())
                            : Lang.t("cmd.dbtest.unit-missing"));
                    sender.sendMessage(Lang.t("cmd.dbtest.events", events.size()));
                });
            } catch (Exception e) {
                plugin.getLogger().severe("dbtest selhal: " + e);
            }
        }, 4L);
    }
}
