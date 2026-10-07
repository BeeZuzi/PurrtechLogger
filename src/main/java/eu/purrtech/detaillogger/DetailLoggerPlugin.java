package eu.purrtech.detaillogger;

import eu.purrtech.detaillogger.command.PurrLogCommand;
import eu.purrtech.detaillogger.db.Database;
import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.EventDao;
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

import java.io.File;

public final class DetailLoggerPlugin extends JavaPlugin {

    private Database database;
    private TemplateRegistry templateRegistry;

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

        getServer().getPluginManager().registerEvents(new PlayerJoinScanListener(itemTracking), this);
        getServer().getPluginManager().registerEvents(new PlayerPresenceListener(playerDirectory), this);
        getServer().getPluginManager().registerEvents(new ItemLifecycleListener(itemTracking), this);
        getServer().getPluginManager().registerEvents(new ItemDestructionListener(itemTracking), this);
        getServer().getPluginManager().registerEvents(new ContainerListener(itemTracking, this), this);
        getServer().getPluginManager().registerEvents(new ShulkerNestingListener(itemTracking), this);
        getServer().getPluginManager().registerEvents(new ItemTransformListener(itemTracking, this), this);
        getServer().getPluginManager().registerEvents(new ShulkerSessionListener(itemTracking, itemTag, eventDao), this);
        getServer().getPluginManager().registerEvents(new ChunkIndexListener(blockIndex, locationDao, this), this);
        getServer().getPluginManager().registerEvents(new BlockLifecycleListener(blockTracking, entityTag, this), this);

        AdminGuiService adminGuiService = setupDisplayGuiIntegration(
                templateDao, eventDao, dupeAlertDao, historyService, playerDirectory, locationDao, lineageDao, itemTracking);

        var purrLogCommand = new PurrLogCommand(this, trackedUnitDao, eventDao, templateRegistry,
                templatesFile, historyService, dupeAlertDao, sweepTask, adminGuiService, playerDirectory);
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
        Bukkit.getScheduler().runTaskTimer(this, sweepTask::runSweep, 20L * 30, 20L * 30);
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
