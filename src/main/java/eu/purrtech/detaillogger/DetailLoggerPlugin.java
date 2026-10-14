package eu.purrtech.detaillogger;

import eu.purrtech.detaillogger.command.PurrLogCommand;
import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.LedgerDao;
import eu.purrtech.detaillogger.db.dao.LineageDao;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import eu.purrtech.detaillogger.db.dao.PlayerDao;
import eu.purrtech.detaillogger.db.dao.TemplateDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.gui.AdminGuiService;
import eu.purrtech.detaillogger.gui.MenuViewLoggingAction;
import eu.purrtech.detaillogger.template.TemplateMatcher;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import eu.purrtech.detaillogger.tracking.BlockIdentityIndex;
import eu.purrtech.detaillogger.tracking.BlockTrackingService;
import eu.purrtech.detaillogger.tracking.HistoryService;
import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.StackDebug;
import eu.purrtech.detaillogger.tracking.PlayerDirectoryService;
import eu.purrtech.detaillogger.tracking.ReconciliationSweepTask;
import eu.purrtech.detaillogger.tracking.listener.BlockLifecycleListener;
import eu.purrtech.detaillogger.tracking.listener.ChunkIndexListener;
import eu.purrtech.detaillogger.tracking.listener.ContainerListener;
import eu.purrtech.detaillogger.tracking.listener.ItemDestructionListener;
import eu.purrtech.detaillogger.tracking.listener.ItemLifecycleListener;
import eu.purrtech.detaillogger.tracking.listener.PlayerJoinScanListener;
import eu.purrtech.detaillogger.tracking.listener.PlayerPresenceListener;
import eu.purrtech.detaillogger.tracking.LedgerService;
import eu.purrtech.detaillogger.tracking.listener.CommandVanishListener;
import eu.purrtech.detaillogger.tracking.listener.InventoryTagListener;
import eu.purrtech.detaillogger.tracking.listener.LedgerListener;
import eu.purrtech.detaillogger.tracking.listener.ItemTransformListener;
import eu.purrtech.detaillogger.tracking.listener.ShulkerNestingListener;
import eu.purrtech.detaillogger.tracking.listener.ShulkerSessionListener;
import eu.purrtech.detaillogger.tracking.pdc.TrackedBlockTag;
import eu.purrtech.detaillogger.tracking.pdc.TrackedEntityTag;
import eu.purrtech.detaillogger.tracking.pdc.TrackedItemTag;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import eu.purrtech.detaillogger.tracking.TagStripper;
import eu.purrtech.detaillogger.tracking.listener.TagStripListener;
import java.io.File;

public final class DetailLoggerPlugin extends JavaPlugin {

    private Database database;
    private TemplateRegistry templateRegistry;
    private LedgerService ledgerService;
    private TagStripper tagStripper;
    private TagStripListener tagStripListener;
    /** Everything that tags/tracks items - unregistered while tracking is switched off. */
    private final java.util.List<org.bukkit.event.Listener> trackingListeners = new java.util.ArrayList<>();
    private boolean trackingEnabled = true;

    @Override
    public void onEnable() {
        getServer().getPluginManager().addPermission(new Permission(PurrLogCommand.ADMIN_PERMISSION,
                "Access to all /purrlog admin commands", PermissionDefault.OP));

        database = new Database(this);
        database.open();

        saveDefaultConfig();
        applyWriteQueueConfig();
        saveResource("templates.yml", false);
        File templatesFile = new File(getDataFolder(), "templates.yml");

        TrackedUnitDao trackedUnitDao = new TrackedUnitDao(database);
        EventDao eventDao = new EventDao(database);
        LocationDao locationDao = new LocationDao(database);
        TemplateDao templateDao = new TemplateDao(database);
        PlayerDao playerDao = new PlayerDao(database);
        templateRegistry = new TemplateRegistry(templatesFile, templateDao, getLogger());

        TrackedItemTag itemTag = new TrackedItemTag(this);
        TrackedBlockTag blockTag = new TrackedBlockTag(this);
        TrackedEntityTag entityTag = new TrackedEntityTag(this);
        TemplateMatcher templateMatcher = new TemplateMatcher(templateRegistry);
        LineageDao lineageDao = new LineageDao(database);
        ItemTrackingService itemTracking = new ItemTrackingService(
                itemTag, templateMatcher, templateRegistry, trackedUnitDao, locationDao, eventDao, lineageDao, getLogger());
        StackDebug.init(getDataFolder(), getLogger(), itemTracking);
        BlockIdentityIndex blockIndex = new BlockIdentityIndex();
        BlockTrackingService blockTracking = new BlockTrackingService(blockTag, itemTag, templateMatcher,
                templateRegistry, trackedUnitDao, locationDao, eventDao, blockIndex, getLogger());
        HistoryService historyService = new HistoryService(trackedUnitDao, eventDao);
        DupeAlertDao dupeAlertDao = new DupeAlertDao(database);
        ReconciliationSweepTask sweepTask = new ReconciliationSweepTask(
                itemTracking, trackedUnitDao, dupeAlertDao, templateRegistry, this, getLogger());
        PlayerDirectoryService playerDirectory = new PlayerDirectoryService(
                playerDao, eventDao, dupeAlertDao, this, getLogger());

        // Safety net against "online" rows left behind by an unclean shutdown - anyone genuinely
        // online will re-fire PlayerJoinEvent once the server (and thus this listener) restarts.
        playerDao.enqueueResetAllOffline();

        trackingListeners.add(new PlayerJoinScanListener(itemTracking));
        getServer().getPluginManager().registerEvents(new PlayerPresenceListener(playerDirectory), this);
        trackingListeners.add(new ItemLifecycleListener(itemTracking));
        trackingListeners.add(new ItemDestructionListener(itemTracking));
        trackingListeners.add(new ContainerListener(itemTracking, this));
        trackingListeners.add(new ShulkerNestingListener(itemTracking));
        trackingListeners.add(new ItemTransformListener(itemTracking, this));
        ledgerService = new LedgerService(itemTracking, templateRegistry, new LedgerDao(database),
                dupeAlertDao, this);
        trackingListeners.add(new LedgerListener(ledgerService, this));
        trackingListeners.add(new CommandVanishListener(itemTracking, this));
        // Reference items and "vanished after a command" units live in the DB; load them for the
        // main-thread fast paths (async: the reads must not run on the server thread).
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                itemTracking.loadReferences(trackedUnitDao.findUuidsByOrigin(ItemTrackingService.ORIGIN_REFERENCE)
                        .stream().map(java.util.UUID::fromString).toList());
                java.util.Map<java.util.UUID, String> vanished = new java.util.HashMap<>();
                locationDao.findByType("COMMAND_VANISHED").forEach((unit, command) ->
                        vanished.put(java.util.UUID.fromString(unit), command != null ? command : "?"));
                itemTracking.loadVanished(vanished);
            } catch (Exception e) {
                getLogger().severe("Nacteni referencnich/zmizelych jednotek selhalo: " + e);
            }
        });
        InventoryTagListener inventoryTagger = new InventoryTagListener(itemTracking, this);
        trackingListeners.add(inventoryTagger);
        trackingListeners.add(new ShulkerSessionListener(itemTracking, itemTag, eventDao));
        getServer().getPluginManager().registerEvents(new ChunkIndexListener(blockIndex, locationDao, this), this);
        getServer().getPluginManager().registerEvents(new BlockLifecycleListener(blockTracking, entityTag, this), this);

        tagStripper = new TagStripper(itemTag, trackedUnitDao, this);
        tagStripListener = new TagStripListener(tagStripper, this);
        trackingEnabled = getConfig().getBoolean("tracking-enabled", true);
        if (trackingEnabled) {
            trackingListeners.forEach(l -> getServer().getPluginManager().registerEvents(l, this));
        } else {
            getServer().getPluginManager().registerEvents(tagStripListener, this);
            getLogger().warning("Sledovani predmetu je VYPNUTE (tracking-enabled: false) - /purrlog tracking on");
        }

        AdminGuiService adminGuiService = setupDisplayGuiIntegration(
                templateDao, eventDao, dupeAlertDao, historyService, playerDirectory, locationDao, lineageDao, itemTracking);

        var purrLogCommand = new PurrLogCommand(this, trackedUnitDao, eventDao, templateRegistry,
                templatesFile, historyService, dupeAlertDao, sweepTask, adminGuiService, playerDirectory,
                inventoryTagger, ledgerService, itemTracking);
        registerCommand("purrlog", "PurrTechDetailLogger admin/debug command", purrLogCommand);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                templateRegistry.reloadBlocking();
            } catch (Exception e) {
                getLogger().severe("Pocatecni nacteni sablon selhalo: " + e);
            }
        });

        // Every 30s: re-derive ground truth from what's physically on online players and
        // cross-check it against the DB - see ReconciliationSweepTask's own docs for scope.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (trackingEnabled) {
                sweepTask.runSweep();
            }
        }, 20L * 30, 20L * 30);
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    /**
     * The kill switch. Off: item listeners are unregistered, every reachable tag is stripped and the
     * item units are retired. On: listeners return; old items get fresh UUIDs as they are met.
     */
    public void setTrackingEnabled(boolean enabled, org.bukkit.command.CommandSender sender) {
        if (enabled == trackingEnabled) {
            return;
        }
        trackingEnabled = enabled;
        getConfig().set("tracking-enabled", enabled);
        saveConfig();
        if (enabled) {
            org.bukkit.event.HandlerList.unregisterAll(tagStripListener);
            trackingListeners.forEach(l -> getServer().getPluginManager().registerEvents(l, this));
            Bukkit.getOnlinePlayers().forEach(ledgerService::initCounts);
            sender.sendMessage("Sledovani ZAPNUTO. Drive sledovane predmety dostanou nove UUID.");
        } else {
            trackingListeners.forEach(org.bukkit.event.HandlerList::unregisterAll);
            getServer().getPluginManager().registerEvents(tagStripListener, this);
            tagStripper.stripEverything(sender);
        }
    }

    public eu.purrtech.detaillogger.db.WriteQueue.Stats writeQueueStats() {
        return database.writeQueue().stats();
    }

    /** Reads {@code max-pending-writes} and {@code event-priority} from config.yml into the DB
     * write queue. Called on enable and again by {@code /purrlog reload}. */
    public void applyWriteQueueConfig() {
        java.util.Map<String, eu.purrtech.detaillogger.db.WritePriority> priorities = new java.util.HashMap<>();
        org.bukkit.configuration.ConfigurationSection section = getConfig().getConfigurationSection("event-priority");
        if (section != null) {
            for (String type : section.getKeys(false)) {
                String value = String.valueOf(section.getString(type)).trim().toUpperCase(java.util.Locale.ROOT);
                try {
                    priorities.put(type.toUpperCase(java.util.Locale.ROOT),
                            eu.purrtech.detaillogger.db.WritePriority.valueOf(value));
                } catch (IllegalArgumentException e) {
                    getLogger().warning("config.yml event-priority." + type + ": neznama hodnota '" + value
                            + "' (HIGHEST/HIGH/MEDIUM/LOW/LOWEST/OFF) - pouzivam MEDIUM");
                }
            }
        }
        database.writeQueue().configure(Math.max(1000, getConfig().getInt("max-pending-writes", 5_000_000)), priorities);
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    /**
     * DisplayGUI is a soft dependency (see paper-plugin.yml) - the admin GUI and
     * {@code [purrtechlog]} action tag only activate if it's actually present. Every class that
     * touches DisplayGUI's API ({@link AdminGuiService}, {@link MenuViewLoggingAction}) is kept
     * out of the always-executed startup path so the JVM never has to resolve DisplayGUI's classes
     * when it isn't installed - only this one guarded call site constructs them.
     */
    private AdminGuiService setupDisplayGuiIntegration(TemplateDao templateDao, EventDao eventDao,
                                                         DupeAlertDao dupeAlertDao, HistoryService historyService,
                                                         PlayerDirectoryService playerDirectory,
                                                         LocationDao locationDao, LineageDao lineageDao,
                                                         ItemTrackingService itemTracking) {
        if (!getServer().getPluginManager().isPluginEnabled("PurrTechDisplayGUI")) {
            getLogger().info("PurrTechDisplayGUI nenalezeno - admin GUI (/purrlog gui) a [purrtechlog] akce nejsou k dispozici.");
            return null;
        }
        AdminGuiService adminGuiService = new AdminGuiService(
                historyService, templateDao, dupeAlertDao, playerDirectory, eventDao, locationDao, lineageDao, itemTracking,
                this, getLogger());
        getServer().getPluginManager().registerEvents(adminGuiService, this);
        MenuViewLoggingAction.register(eventDao);
        getLogger().info("DisplayGUI integrace aktivni (/purrlog gui, [purrtechlog] akce).");
        return adminGuiService;
    }
}
