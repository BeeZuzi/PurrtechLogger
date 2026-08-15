package eu.purrtech.detaillogger.gui;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.PlayerRecord;
import eu.purrtech.detaillogger.db.dao.TemplateDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;
import eu.purrtech.detaillogger.tracking.HistoryService;
import eu.purrtech.detaillogger.tracking.PlayerDirectoryService;
import eu.purrtech.displaygui.API.PageType;
import eu.purrtech.displaygui.API.data.buttonData.ButtonData;
import eu.purrtech.displaygui.API.data.buttonData.ItemButtonData;
import eu.purrtech.displaygui.API.data.buttonData.ScrollButtonData;
import eu.purrtech.displaygui.API.data.layerData.DisplayLayerData;
import eu.purrtech.displaygui.API.data.layerData.ItemDisplayLayerData;
import eu.purrtech.displaygui.API.data.layerData.LayersData;
import eu.purrtech.displaygui.API.data.layerData.TextDisplayLayerData;
import eu.purrtech.displaygui.API.data.screenPageData.PageData;
import eu.purrtech.displaygui.API.data.screenPageData.ScreenPageData;
import eu.purrtech.displaygui.API.events.MenuButtonClickEvent;
import eu.purrtech.displaygui.Internal.PurrTechDisplayGUI;
import eu.purrtech.displaygui.Internal.manager.DisplayManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import eu.purrtech.displaygui.API.DisplayGuiAPI;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * In-game admin viewer built on DisplayGUI: search a tracked unit by UUID, browse its history, or
 * list unresolved dupe alerts. Every page is built fresh from the DB per open (nothing is
 * pre-registered) since the content is entirely dynamic.
 * <p>
 * {@code ButtonType.TEXT_PLACE} (DisplayGUI's own chat-input button) has no supported way for an
 * addon to receive the typed text back - {@link #beginSearch} builds the "type a UUID in chat"
 * flow by hand instead, via a plain button + this class's own {@link AsyncChatEvent} listener.
 */
public final class AdminGuiService implements Listener {

    private static final String GUI_PATH = "purrtechlogger";
    private static final Color BUTTON_BACKGROUND = Color.fromARGB(230, 40, 40, 40);
    private static final Color PANEL_BACKGROUND = Color.fromARGB(210, 25, 25, 25);
    private static final double MENU_DISTANCE_PIXELS = 80;
    private static final int PLAYERS_PAGE_SIZE = 6;

    private enum SearchMode { ITEM, PLAYER }

    private final HistoryService historyService;
    private final TemplateDao templateDao;
    private final DupeAlertDao dupeAlertDao;
    private final PlayerDirectoryService playerDirectory;
    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, SearchMode> awaitingSearchInput = new ConcurrentHashMap<>();

    public AdminGuiService(HistoryService historyService, TemplateDao templateDao, DupeAlertDao dupeAlertDao,
                            PlayerDirectoryService playerDirectory, Plugin plugin, Logger logger) {
        this.historyService = historyService;
        this.templateDao = templateDao;
        this.dupeAlertDao = dupeAlertDao;
        this.playerDirectory = playerDirectory;
        this.plugin = plugin;
        this.logger = logger;
    }

    public void openMainMenu(Player player) {
        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(navButton("players", -1.5, -0.6, 0.04, "Hraci", e -> openPlayerList(player, 0)));
        buttons.add(navButton("search", -1.5, -1.2, 0.04, "Hledat podle UUID", e -> beginSearch(player)));
        buttons.add(navButton("alerts", -1.5, -1.8, 0.05, "Nevyresene alerty", e -> openAlerts(player)));
        buttons.add(navButton("close", -1.5, -2.4, 0.05, "Zavrit", e -> DisplayGuiAPI.closeMenu(player)));
        buttons.add(navButton("player-search", -1.5, -3.0, 0.05, "Hledat hrace", e -> beginPlayerSearch(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:main", MENU_DISTANCE_PIXELS);
//        DisplayGuiAPI.closeMenu(player);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private void beginSearch(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.ITEM);
        DisplayGuiAPI.closeMenu(player);
        player.sendMessage("Napis do chatu UUID hledaneho itemu (nebo 'zrusit').");
    }

    private void beginPlayerSearch(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.PLAYER);
        DisplayGuiAPI.closeMenu(player);
        player.sendMessage("Napis do chatu nick nebo UUID hledaneho hrace (nebo 'zrusit').");
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        SearchMode mode = awaitingSearchInput.remove(player.getUniqueId());
        if (mode == null) {
            return;
        }
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (raw.equalsIgnoreCase("zrusit")) {
                player.sendMessage("Hledani zruseno.");
                return;
            }
            if (mode == SearchMode.ITEM) {
                openDetail(player, raw);
            } else {
                openPlayerSearch(player, raw);
            }
        });
    }

    public void openDetail(Player player, String rawUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid.trim());
        } catch (IllegalArgumentException e) {
            player.sendMessage("Neplatne UUID: " + rawUuid);
            return;
        }
        String uuidString = uuid.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<HistoryService.UnitHistory> found = historyService.lookup(uuidString);
                if (found.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Item s UUID '" + uuidString + "' neni znam."));
                    return;
                }
                String material = templateDao.findMaterialById(found.get().unit().templateId());
                Bukkit.getScheduler().runTask(plugin, () -> openDetailPage(player, found.get(), material));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openDetailPage(Player player, HistoryService.UnitHistory history, String materialName) {
        TrackedUnitRecord unit = history.unit();
        Material material = materialName != null ? Material.matchMaterial(materialName) : null;
        ItemStack icon = new ItemStack(material != null ? material : Material.BARRIER);

        List<String> infoLines = new ArrayList<>();
        infoLines.add("UUID: " + unit.uuid());
        infoLines.add("Kind: " + unit.kind() + "  Origin: " + unit.origin());
        infoLines.add("Alive: " + unit.alive());
        if (!unit.alive()) {
            infoLines.add("Znicen: " + unit.destroyedCause() + " @ " + formatTime(unit.destroyedAt()));
        }
        if (unit.duplicatedFromUuid() != null) {
            infoLines.add("Duplikat z: " + unit.duplicatedFromUuid());
        }

        List<String> historyLines = new ArrayList<>();
        for (EventRecord e : history.events()) {
            String where = e.world() != null ? " @ " + e.world() + " " + e.x() + "," + e.y() + "," + e.z() : "";
            String playerUUID = e.playerUuid() != null ? " hrac=" + e.playerUuid() : "";
            if (!playerUUID.isEmpty()) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(e.playerUuid());
                if (offlinePlayer.isOnline()) {
                    Player onlinePlayer = offlinePlayer.getPlayer();
                    if (onlinePlayer != null) {
                        playerUUID = playerUUID + " alias=" + onlinePlayer.displayName().insertion();
                    }
                } else {
                    playerUUID = playerUUID + " alias: " + offlinePlayer.getPlayerProfile().getName();
                }


            }
            historyLines.add(formatTime(e.timestamp()) + " " + e.eventType() + where + " " + playerUUID + " " + e.detail());
        }

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(iconButton(-0, 1.0, 0.05, icon));
        buttons.add(infoTextButton(-0, -1.25, 0.05, infoLines));
        buttons.add(scrollListButton("history", -3, -1, 0.05, historyLines, "(zadna historie)"));
        buttons.add(navButton("back", -1.5, -2, -0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:detail:" + unit.uuid(), MENU_DISTANCE_PIXELS);
//        DisplayGuiAPI.closeMenu(player);
        DisplayGuiAPI.openMenu(player, screen);
    }

    public void openAlerts(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<DupeAlertRecord> alerts = dupeAlertDao.findUnresolved(50);
                Bukkit.getScheduler().runTask(plugin, () -> openAlertsPage(player, alerts));
            } catch (SQLException e) {
                logger.severe("Admin GUI alerts lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openAlertsPage(Player player, List<DupeAlertRecord> alerts) {
        List<String> lines = new ArrayList<>();
        for (DupeAlertRecord a : alerts) {
            lines.add("[" + a.severity() + "] " + a.note() + " uuid=" + a.unitUuid()
                    + " observed=" + a.observedCount() + "/" + a.expectedAliveCount());
        }

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(scrollListButton("alerts", -1.5, -1.55, 0.05, lines, "(zadne nevyresene alerty)"));
        buttons.add(navButton("back", -1.5, -2.15, 0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:alerts", MENU_DISTANCE_PIXELS);
//        DisplayGuiAPI.closeMenu(player);
        DisplayGuiAPI.openMenu(player, screen);
    }

    public void openPlayerList(Player player, int offset) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerRecord> all = playerDirectory.listPlayers();
                Bukkit.getScheduler().runTask(plugin, () -> openPlayerListPage(player, all, offset, null));
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni seznamu hracu selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openPlayerSearch(Player player, String query) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerRecord> results = playerDirectory.searchPlayers(query);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (results.isEmpty()) {
                        player.sendMessage("Zadny hrac neodpovida '" + query + "'.");
                        return;
                    }
                    if (results.size() == 1) {
                        openPlayerDetail(player, results.get(0).uuid());
                        return;
                    }
                    openPlayerListPage(player, results, 0, query);
                });
            } catch (SQLException e) {
                logger.severe("Admin GUI hledani hrace selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openPlayerListPage(Player player, List<PlayerRecord> all, int offset, String searchQuery) {
        int clampedOffset = all.isEmpty() ? 0 : Math.max(0, Math.min(offset, all.size() - 1));
        int end = Math.min(clampedOffset + PLAYERS_PAGE_SIZE, all.size());
        List<PlayerRecord> page = all.subList(clampedOffset, end);

        List<ButtonData> buttons = new ArrayList<>();
        double y = 1.5;
        for (PlayerRecord p : page) {
            String status = p.online() ? "[ONLINE] " : "[OFFLINE] ";
            String suffix = p.online() ? "" : " (naposledy " + formatTime(p.lastSeenAt()) + ")";
            String label = status + p.currentName() + suffix;
            buttons.add(navButton("player-" + p.uuid(), -1.5, y, 0.05, label, e -> openPlayerDetail(player, p.uuid())));
            y -= 0.6;
        }

        List<String> infoLines = new ArrayList<>();
        infoLines.add(searchQuery != null ? "Vysledky hledani: '" + searchQuery + "'" : "Vsichni hraci");
        infoLines.add(all.isEmpty() ? "0 hracu" : (clampedOffset + 1) + "-" + end + " z " + all.size());
        buttons.add(infoTextButton(-1.5, 2.1, 0.04, infoLines));

        if (clampedOffset > 0) {
            buttons.add(navButton("prev", -2.7, y - 0.3, 0.05, "Predchozi",
                    e -> openPlayerListPage(player, all, clampedOffset - PLAYERS_PAGE_SIZE, searchQuery)));
        }
        if (end < all.size()) {
            buttons.add(navButton("next", -0.3, y - 0.3, 0.05, "Dalsi",
                    e -> openPlayerListPage(player, all, clampedOffset + PLAYERS_PAGE_SIZE, searchQuery)));
        }
        buttons.add(navButton("back", -1.5, y - 0.9, 0.05, "Hlavni menu", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:players:" + clampedOffset, MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    public void openPlayerDetail(Player player, String targetUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<PlayerDirectoryService.PlayerProfile> found = playerDirectory.lookupPlayer(targetUuid);
                if (found.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Hrac s UUID '" + targetUuid + "' neni znam."));
                    return;
                }
                PlayerDirectoryService.PlayerActivity activity = playerDirectory.findActivity(targetUuid, 100);
                Bukkit.getScheduler().runTask(plugin, () -> openPlayerDetailPage(player, found.get(), activity));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail hrace selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openPlayerDetailPage(Player player, PlayerDirectoryService.PlayerProfile profile,
                                       PlayerDirectoryService.PlayerActivity activity) {
        PlayerRecord unit = profile.player();

        List<String> infoLines = new ArrayList<>();
        infoLines.add("Nick: " + unit.currentName());
        infoLines.add("UUID: " + unit.uuid());
        infoLines.add(unit.online() ? "Stav: ONLINE" : "Stav: OFFLINE (naposledy " + formatTime(unit.lastSeenAt()) + ")");
        infoLines.add("Prvni pripojeni: " + formatTime(unit.firstJoinedAt()));
        String priorNames = profile.nameHistory().stream()
                .map(n -> n.name() + " (" + formatTime(n.changedAt()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("zadne");
        infoLines.add("Predchozi nicky: " + priorNames);

        record ActivityLine(long timestamp, String text) {
        }
        List<ActivityLine> lines = new ArrayList<>();
        for (EventRecord e : activity.events()) {
            String where = e.world() != null ? " @ " + e.world() + " " + e.x() + "," + e.y() + "," + e.z() : "";
            String gamemode = e.gamemode() != null ? " gamemode=" + e.gamemode() : "";
            String tag = e.eventType().equals("CREATIVE_DUPLICATE") ? "[DUPE-CREATIVE] " : "";
            lines.add(new ActivityLine(e.timestamp(),
                    formatTime(e.timestamp()) + " " + tag + e.eventType() + where + gamemode));
        }
        for (DupeAlertRecord a : activity.alerts()) {
            lines.add(new ActivityLine(a.detectedAt(),
                    formatTime(a.detectedAt()) + " [DUPE-BUG] [" + a.severity() + "] " + a.note()
                            + " uuid=" + a.unitUuid()));
        }
        List<String> activityLines = lines.stream()
                .sorted(Comparator.comparingLong(ActivityLine::timestamp).reversed())
                .map(ActivityLine::text)
                .toList();

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(infoTextButton(-0, 1.0, 0.05, infoLines));
        buttons.add(scrollListButton("activity", -3, -1, 0.05, activityLines, "(zadna aktivita)"));
        buttons.add(navButton("back", -1.5, -2, -0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:player:" + unit.uuid(), MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private PageData backgroundPage() {
        TextDisplayLayerData bg = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "bg", 1)
                .setText(List.of("PurrTechDetailLogger", "", "", ""))
                .setBackground(Color.fromARGB(100, 15, 15, 15))
                .setAutoFitText(true);

        bg.setScale(bg.getVectorWithPixels(30,30,0));
        bg.setWidth(3);
        bg.setHeight(3);
        LayersData design = new LayersData(List.of(bg), GUI_PATH + ":background", GUI_PATH);
        return new PageData(0, 0, 0, design, PageType.NORMAL);
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(List.of(label))
                .setBackground(BUTTON_BACKGROUND);
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(60, 5)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                .build();
    }

    private ButtonData infoTextButton(double x, double y, double z, List<String> lines) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "info-text", 1)
                .setText(lines)
                .setBackground(PANEL_BACKGROUND);
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":info", GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(30, 10)
                .layers(design)
                .id("info")
                .build();
    }

    private ButtonData iconButton(double x, double y, double z, ItemStack icon) {
        ItemDisplayLayerData itemLayer = new ItemDisplayLayerData(0, 0, 0, GUI_PATH, "icon-item", 1)
                .setItemStack(icon);
        LayersData design = new LayersData(List.of(itemLayer), GUI_PATH + ":icon", GUI_PATH);
        return ItemButtonData.itemButtonBuilder()
                .at(x, y, z)
                .size(10, 32)
                .layers(design)
                .id("icon")
                .itemLayer(1)
                .build();
    }

    private ButtonData scrollListButton(String id, double x, double y, double z, List<String> items, String emptyLabel) {
        TextDisplayLayerData content = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-content", 1)
                .setBackground(PANEL_BACKGROUND);
        LayersData design = new LayersData(List.of(content), GUI_PATH + ":" + id, GUI_PATH);
        return ScrollButtonData.scrollButtonBuilder()
                .at(x, y, z)
                .size(20, 130)
                .layers(design)
                .id(id)
                .contentLayer(1)
                .visibleRows(4)
                .items(items.isEmpty() ? List.of(emptyLabel) : items)
                .build();
    }

    private static String formatTime(Long epochMillis) {
        return epochMillis == null ? "?" : Instant.ofEpochMilli(epochMillis).toString();
    }
}
