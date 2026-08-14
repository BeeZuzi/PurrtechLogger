package eu.purrtech.detaillogger.command;

import eu.purrtech.detaillogger.DetailLoggerPlugin;
import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.TrackedUnitDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;
import eu.purrtech.detaillogger.gui.AdminGuiService;
import eu.purrtech.detaillogger.template.TemplateRegistry;
import eu.purrtech.detaillogger.tracking.HistoryService;
import eu.purrtech.detaillogger.tracking.ReconciliationSweepTask;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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

    private final DetailLoggerPlugin plugin;
    private final TrackedUnitDao trackedUnitDao;
    private final EventDao eventDao;
    private final TemplateRegistry templateRegistry;
    private final File templatesFile;
    private final HistoryService historyService;
    private final DupeAlertDao dupeAlertDao;
    private final ReconciliationSweepTask sweepTask;
    private final AdminGuiService adminGuiService;

    public PurrLogCommand(DetailLoggerPlugin plugin, TrackedUnitDao trackedUnitDao, EventDao eventDao,
                           TemplateRegistry templateRegistry, File templatesFile, HistoryService historyService,
                           DupeAlertDao dupeAlertDao, ReconciliationSweepTask sweepTask,
                           AdminGuiService adminGuiService) {
        this.plugin = plugin;
        this.trackedUnitDao = trackedUnitDao;
        this.eventDao = eventDao;
        this.templateRegistry = templateRegistry;
        this.templatesFile = templatesFile;
        this.historyService = historyService;
        this.dupeAlertDao = dupeAlertDao;
        this.sweepTask = sweepTask;
        this.adminGuiService = adminGuiService;
    }

    @Override
    public @Nullable String permission() {
        return ADMIN_PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack commandSourceStack, String[] args) {
        CommandSender sender = commandSourceStack.getSender();

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            runReload(sender);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("template") && args[1].equalsIgnoreCase("import")) {
            runTemplateImport(sender, args[2]);
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
        if (args.length == 1 && args[0].equalsIgnoreCase("sweep")) {
            sweepTask.runSweep();
            sender.sendMessage("Reconciliation sweep spusten.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            runGui(sender);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("dbtest")) {
            runDbTest(sender);
            return;
        }
        sendHelp(sender);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("=== PurrTechDetailLogger ===");
        sender.sendMessage("/purrlog gui - otevri admin GUI (vyzaduje DisplayGUI)");
        sender.sendMessage("/purrlog history <uuid> - historie sledovane jednotky");
        sender.sendMessage("/purrlog alerts - nevyresene dupe alerty");
        sender.sendMessage("/purrlog sweep - rucne spusti reconciliation sweep");
        sender.sendMessage("/purrlog reload - znovu nacte templates.yml");
        sender.sendMessage("/purrlog template import <klic> - vytvori sablonu z drzeneho itemu");
        sender.sendMessage("/purrlog dbtest - overi DB potrubi zapisem/ctenim testovaciho zaznamu");
    }

    private void runGui(CommandSender sender) {
        if (adminGuiService == null) {
            sender.sendMessage("DisplayGUI neni nainstalovane/aktivni - admin GUI neni k dispozici.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tento prikaz musi spustit hrac.");
            return;
        }
        adminGuiService.openMainMenu(player);
    }

    private void runAlerts(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<DupeAlertRecord> alerts = dupeAlertDao.findUnresolved(20);
                Bukkit.getScheduler().runTask(plugin, () -> reportAlerts(sender, alerts));
            } catch (Exception e) {
                plugin.getLogger().severe("alerts lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void reportAlerts(CommandSender sender, List<DupeAlertRecord> alerts) {
        if (alerts.isEmpty()) {
            sender.sendMessage("Zadne nevyresene dupe alerty.");
            return;
        }
        sender.sendMessage("Nevyresene alerty (" + alerts.size() + "):");
        for (DupeAlertRecord a : alerts) {
            String where = a.world() != null ? " @ " + a.world() + " " + a.x() + "," + a.y() + "," + a.z() : "";
            sender.sendMessage(" - [" + a.severity() + "] " + a.note() + " uuid=" + a.unitUuid()
                    + " observed=" + a.observedCount() + " expected=" + a.expectedAliveCount()
                    + " hrac=" + a.playerUuid() + where + " " + formatTime(a.detectedAt()));
        }
    }

    private void runHistory(CommandSender sender, String uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<HistoryService.UnitHistory> found = historyService.lookup(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> reportHistory(sender, uuid, found));
            } catch (Exception e) {
                plugin.getLogger().severe("history lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void reportHistory(CommandSender sender, String uuid, Optional<HistoryService.UnitHistory> found) {
        if (found.isEmpty()) {
            sender.sendMessage("Item s UUID '" + uuid + "' neni znam.");
            return;
        }
        TrackedUnitRecord unit = found.get().unit();
        sender.sendMessage("=== " + unit.uuid() + " ===");
        sender.sendMessage("sablona id=" + unit.templateId() + " kind=" + unit.kind()
                + " origin=" + unit.origin() + " alive=" + unit.alive());
        if (!unit.alive()) {
            sender.sendMessage("znicen: " + unit.destroyedCause() + " v " + formatTime(unit.destroyedAt()));
        }
        List<EventRecord> events = found.get().events();
        sender.sendMessage("historie (" + events.size() + "):");
        for (EventRecord e : events) {
            String where = e.world() != null ? " @ " + e.world() + " " + e.x() + "," + e.y() + "," + e.z() : "";
            sender.sendMessage(" - " + formatTime(e.timestamp()) + " " + e.eventType() + where);
        }
    }

    private static String formatTime(Long epochMillis) {
        return epochMillis == null ? "?" : Instant.ofEpochMilli(epochMillis).toString();
    }

    private void runReload(CommandSender sender) {
        sender.sendMessage("Nacitam sablony...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                templateRegistry.reloadBlocking();
                int loaded = templateRegistry.definitions().size();
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("Hotovo: " + loaded + " sablon nacteno."));
            } catch (Exception e) {
                plugin.getLogger().severe("Reload sablon selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Reload selhal, viz konzole."));
            }
        });
    }

    private void runTemplateImport(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tento prikaz musi spustit hrac (drzi item v ruce, ktery se ma stat sablonou).");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage("V ruce nic nedrzis.");
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(templatesFile);
        String path = "templates." + key;
        if (yaml.contains(path)) {
            sender.sendMessage("Sablona s klicem '" + key + "' uz existuje.");
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
        yaml.set(path + ".track-items", true);
        yaml.set(path + ".track-blocks", false);

        try {
            yaml.save(templatesFile);
        } catch (IOException e) {
            sender.sendMessage("Ulozeni templates.yml selhalo: " + e.getMessage());
            return;
        }

        sender.sendMessage("Sablona '" + key + "' pridana (material=" + held.getType() + "). Spoustim reload...");
        runReload(sender);
    }

    private void runDbTest(CommandSender sender) {
        String uuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        // template_id 0 is the inert bootstrap fixture seeded by V1__init.sql for exactly this test.
        trackedUnitDao.enqueueUpsert(uuid, 0, "ITEM", "DBTEST", null, now, true, null, null);
        eventDao.enqueue(uuid, "DBTEST_PING", now, "world", 0, 64, 0, null, null);

        sender.sendMessage("Zapsáno testovací UUID " + uuid + ", čtu zpět...");

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                TrackedUnitRecord unit = trackedUnitDao.findByUuid(uuid);
                List<EventRecord> events = eventDao.findByUnit(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("tracked_units: " + (unit != null
                            ? "OK (origin=" + unit.origin() + ", alive=" + unit.alive() + ")"
                            : "CHYBI - zapis se nepropsal"));
                    sender.sendMessage("events: " + events.size() + " zaznam(u)");
                });
            } catch (Exception e) {
                plugin.getLogger().severe("dbtest selhal: " + e);
            }
        }, 4L);
    }
}
