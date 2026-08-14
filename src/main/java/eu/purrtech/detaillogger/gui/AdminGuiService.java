package eu.purrtech.detaillogger.gui;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.TemplateDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;
import eu.purrtech.detaillogger.tracking.HistoryService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.lordking.displaygui.API.DisplayGuiAPI;
import org.lordking.displaygui.API.PageType;
import org.lordking.displaygui.API.data.buttonData.ButtonData;
import org.lordking.displaygui.API.data.buttonData.ItemButtonData;
import org.lordking.displaygui.API.data.buttonData.ScrollButtonData;
import org.lordking.displaygui.API.data.layerData.ItemDisplayLayerData;
import org.lordking.displaygui.API.data.layerData.LayersData;
import org.lordking.displaygui.API.data.layerData.TextDisplayLayerData;
import org.lordking.displaygui.API.data.screenPageData.PageData;
import org.lordking.displaygui.API.data.screenPageData.ScreenPageData;
import org.lordking.displaygui.API.events.MenuButtonClickEvent;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    private final HistoryService historyService;
    private final TemplateDao templateDao;
    private final DupeAlertDao dupeAlertDao;
    private final Plugin plugin;
    private final Logger logger;
    private final Set<UUID> awaitingSearchInput = ConcurrentHashMap.newKeySet();

    public AdminGuiService(HistoryService historyService, TemplateDao templateDao, DupeAlertDao dupeAlertDao,
                            Plugin plugin, Logger logger) {
        this.historyService = historyService;
        this.templateDao = templateDao;
        this.dupeAlertDao = dupeAlertDao;
        this.plugin = plugin;
        this.logger = logger;
    }

    public void openMainMenu(Player player) {
        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(navButton("search", -1.0, 0.5, 0.05, "Hledat podle UUID", e -> beginSearch(player)));
        buttons.add(navButton("alerts", -1.0, 0.2, 0.05, "Nevyresene alerty", e -> openAlerts(player)));
        buttons.add(navButton("close", -1.0, -0.1, 0.05, "Zavrit", e -> DisplayGuiAPI.closeMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:main", MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private void beginSearch(Player player) {
        awaitingSearchInput.add(player.getUniqueId());
        DisplayGuiAPI.closeMenu(player);
        player.sendMessage("Napis do chatu UUID hledaneho itemu (nebo 'zrusit').");
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingSearchInput.remove(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (raw.equalsIgnoreCase("zrusit")) {
                player.sendMessage("Hledani zruseno.");
                return;
            }
            openDetail(player, raw);
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
            historyLines.add(formatTime(e.timestamp()) + " " + e.eventType() + where);
        }

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(iconButton(-1.3, 0.9, 0.05, icon));
        buttons.add(infoTextButton(-0.9, 0.9, 0.05, infoLines));
        buttons.add(scrollListButton("history", -1.3, 0.35, 0.05, historyLines, "(zadna historie)"));
        buttons.add(navButton("back", -1.3, -0.15, 0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:detail:" + unit.uuid(), MENU_DISTANCE_PIXELS);
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
        buttons.add(scrollListButton("alerts", -1.3, 0.6, 0.05, lines, "(zadne nevyresene alerty)"));
        buttons.add(navButton("back", -1.3, -0.15, 0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:alerts", MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private PageData backgroundPage() {
        TextDisplayLayerData bg = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "bg", 1)
                .setText(List.of("PurrTechDetailLogger"))
                .setBackground(Color.fromARGB(200, 15, 15, 15));
        LayersData design = new LayersData(List.of(bg), GUI_PATH + ":background", GUI_PATH);
        return new PageData(-1.5, 1.15, 0, design, PageType.NORMAL);
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(List.of(label))
                .setBackground(BUTTON_BACKGROUND);
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(140, 20)
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
                .size(160, 60)
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
                .size(32, 32)
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
                .size(220, 130)
                .layers(design)
                .id(id)
                .contentLayer(1)
                .visibleRows(8)
                .items(items.isEmpty() ? List.of(emptyLabel) : items)
                .build();
    }

    private static String formatTime(Long epochMillis) {
        return epochMillis == null ? "?" : Instant.ofEpochMilli(epochMillis).toString();
    }
}
