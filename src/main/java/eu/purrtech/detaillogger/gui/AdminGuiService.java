package eu.purrtech.detaillogger.gui;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.db.dao.LocationDao;
import eu.purrtech.detaillogger.db.dao.PlayerRecord;
import eu.purrtech.detaillogger.db.dao.TemplateDao;
import eu.purrtech.detaillogger.db.dao.TrackedUnitRecord;
import eu.purrtech.detaillogger.db.dao.UnitLocationRecord;
import eu.purrtech.detaillogger.tracking.HistoryService;
import eu.purrtech.detaillogger.tracking.ItemTrackingService;
import eu.purrtech.detaillogger.tracking.NearbyPlayers;
import eu.purrtech.detaillogger.tracking.PlayerDirectoryService;
import eu.purrtech.detaillogger.tracking.ShulkerSessionLog;
import eu.purrtech.detaillogger.util.EventLineFormatter;
import eu.purrtech.displaygui.API.PageType;
import eu.purrtech.displaygui.API.actions.MenuActionContext;
import eu.purrtech.displaygui.API.data.buttonData.ButtonData;
import eu.purrtech.displaygui.API.data.buttonData.ButtonListScrollButtonData;
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
import eu.purrtech.displaygui.Internal.buttons.Button;
import eu.purrtech.displaygui.Internal.manager.DisplayManager;
import eu.purrtech.displaygui.Internal.utils.ScreenPage;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;
import eu.purrtech.displaygui.API.DisplayGuiAPI;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Every input in this GUI is driven purely by menu buttons - see {@link #openKeyboardPage} for the
 * on-screen keyboard used for UUID/player-name entry. {@code ButtonType.TEXT_PLACE} (DisplayGUI's
 * own chat-input button) has no supported way for an addon to receive the typed text back, so free
 * text is built up one button click at a time instead of via chat.
 */
public final class AdminGuiService implements Listener {

    private static final String GUI_PATH = "purrtechlogger";
    private static final Color BUTTON_BACKGROUND = Color.fromARGB(230, 40, 40, 40);
    private static final Color BUTTON_BACKGROUND_ACTIVE = Color.fromARGB(230, 70, 100, 40);
    /** Dim/reddish - a calendar day that can't be picked (future date, or would cross the other
     * already-set Od/Do bound) - see {@link #isValidEventsDate}. */
    private static final Color BUTTON_BACKGROUND_DISABLED = Color.fromARGB(180, 60, 25, 25);
    private static final Color PANEL_BACKGROUND = Color.fromARGB(210, 25, 25, 25);
    private static final double MENU_DISTANCE_PIXELS = 80;
    private static final int PLAYERS_PAGE_SIZE = 6;
    private static final int EVENTS_LIST_LIMIT = 300;
    /** Rows visible at once in the events {@code button_list_scroll} - per "Použij na ty události
     * button_list_scroll", replacing the old 5-per-page prev/next pagination with one scrollable
     * list holding every matching event (up to {@link #EVENTS_LIST_LIMIT}) at once. See
     * {@link #openEventsListPage}. */
    private static final int EVENTS_VISIBLE_ROWS = 5;
    /** Small Y-axis rotation applied to each event-row button so this far-right-of-center column
     * angles back toward the player instead of sitting edge-on - per "natoč to na hráče" and the
     * "individual display layers can be rotated" capability documented in
     * [[reference-purrtechdisplaygui-coordinate-rules]]. Sign/magnitude picked from that section's
     * general guidance, NOT yet confirmed in-game - if it turns the wrong way, flip the sign via
     * the in-editor "Rotace" tool or here. */
    private static final float EVENTS_LIST_ROTATION_Y_DEGREES = -28f; // was -12, "ještě nakloň o trošku"
    /** Depth of the whole events list (blocks, + = toward the player) - was a hardcoded 0.05. */
    private static final double EVENTS_LIST_Z = 0.75;

    /**
     * Explicit screen size (blocks), applied to every page's background layer so
     * {@code ScreenPage#setLocs} resolves {@code screenWidth}/{@code screenHeight} from a value that
     * actually matches the real button-layout footprint, not just the leftover default of the
     * smallest background layer. {@link #CENTER_X}/{@link #CENTER_Y} - NOT (0,0) - is the point
     * directly in front of the player: every x/y below is authored as {@code CENTER_X + relX} /
     * {@code CENTER_Y + relY} via {@link #cx}/{@link #cy}, not as a raw absolute coordinate. See
     * [[reference-purrtechdisplaygui-coordinate-rules]] for the derivation (an unshifted background
     * at local (0,0) sits with its own center pinned to the top-left corner, which is exactly the
     * "menu ~3 blocks to my left" bug).
     */
    private static final double SCREEN_WIDTH_BLOCKS = 9.0;
    private static final double SCREEN_HEIGHT_BLOCKS = 6.0;
    private static final double CENTER_X = SCREEN_WIDTH_BLOCKS / 2.0;
    private static final double CENTER_Y = SCREEN_HEIGHT_BLOCKS / 2.0;

    /** Category filter shown on the events page - excludes {@code DBTEST_PING} (dbtest-only noise). */
    private static final List<String> EVENT_CATEGORIES = List.of(
            "GENESIS", "PLACED", "MOVED", "MERGED", "SEEN", "SPAWNED",
            "DROPPED", "PICKED_UP", "DESTROYED", "CREATIVE_DUPLICATE", "VIEWED_IN_MENU",
            ShulkerSessionLog.EVENT_TYPE);

    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("GENESIS", "Vznik"),
            Map.entry("PLACED", "Polozeno"),
            Map.entry("MOVED", "Presun"),
            Map.entry("MERGED", "Slouceno"),
            Map.entry("SEEN", "Spatreno"),
            Map.entry("SPAWNED", "Spawnuto"),
            Map.entry("DROPPED", "Vyhozeno"),
            Map.entry("PICKED_UP", "Sebrano"),
            Map.entry("DESTROYED", "Znicen"),
            // Was "Creative dupe" (13 chars) - the one label wide enough to force an oversized grid
            // step on its own; shortened so the category grid's column width is driven by the more
            // typical 6-8 char labels instead. See [[reference-purrtechdisplaygui-coordinate-rules]].
            Map.entry("CREATIVE_DUPLICATE", "Dupe"),
            Map.entry("VIEWED_IN_MENU", "V menu"),
            Map.entry(ShulkerSessionLog.EVENT_TYPE, "Shulker"));

    /** Monday-first weekday header row for {@link #openCalendarPage}, diacritics stripped per the
     * project's no-diacritics-in-labels convention (Pondeli..Nedele). */
    private static final List<String> WEEKDAY_HEADERS = List.of("PO", "UT", "ST", "CT", "PA", "SO", "NE");
    private static final List<String> MONTH_NAMES = List.of(
            "Leden", "Unor", "Brezen", "Duben", "Kveten", "Cerven",
            "Cervenec", "Srpen", "Zari", "Rijen", "Listopad", "Prosinec");

    /** Manual-entry fallback for the calendar (see {@link #beginManualDateInput}) - same formats
     * the old chat-only date flow accepted, still validated by {@link #isValidEventsDate}. */
    private static final DateTimeFormatter DATE_TIME_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 1 block = 16 px ({@code TextDisplayLayerData.PIXEL = 0.0625}) - used to size a button's
     * Interaction hitbox from its text layer's actual rendered size instead of a hand-picked flat
     * default. See {@link #buildTextButton} and [[reference-purrtechdisplaygui-coordinate-rules]]. */
    private static final double PIXELS_PER_BLOCK = 16.0;
    private static final double HITBOX_PADDING_PX = 4;
    private static final double HITBOX_MIN_WIDTH_PX = 20;
    // Height stays a small fixed value (matches the old flat default that was already proven fine
    // at a 0.35-block row step) instead of estimateContentHeightBlocks() - that method returns the
    // font's nominal line-height metric (~0.56 blocks), well above the real glyph ink height, which
    // would make hitboxes taller than the label actually looks and reintroduce row overlap in tight
    // grids (calendar, category filter) that auto-width alone doesn't have.
    private static final double HITBOX_HEIGHT_PX = 5;

    /** {@link #compactButton} target: a label whose natural (scale-1) width exceeds this gets
     * uniformly scaled down (never below {@code COMPACT_MIN_TEXT_SCALE}) so it still fits the
     * category grid's 2.0-block column step with room to spare - see {@link #buildTextButton}.
     * NOT done via {@code TextDisplayLayerData.setTextFit}'s char-count cap: that API always wraps
     * onto a 2nd physical line once a label exceeds the cap (it has no "shrink on one line" mode),
     * which is what caused labels to visibly wrap instead of just shrinking. See
     * [[reference-purrtechdisplaygui-coordinate-rules]]. */
    private static final double COMPACT_TARGET_WIDTH_BLOCKS = 1.7;
    private static final float COMPACT_MIN_TEXT_SCALE = 0.5f;

    private enum SearchMode { PLAYER, EVENTS_DATE, KEYBOARD_MANUAL }

    /** Per-player state for the events page - active category (null = all), optional time bounds,
     * and whether timestamps render as absolute dates or "pred X". No more pagination offset - the
     * events list is a single {@code button_list_scroll} now, scrolled by mouse wheel instead of
     * paged with prev/next buttons. */
    private static final class EventsFilter {
        private String activeCategory;
        private Long from;
        private Long to;
        private boolean relativeTime = false;
    }

    /** Per-player state for the on-screen calendar (see {@link #openEventsCalendar}) - which bound
     * it's picking a day for ("Od"/"Do") and which month is currently shown. */
    private static final class CalendarState {
        private final boolean from;
        private YearMonth month;

        CalendarState(boolean from, YearMonth month) {
            this.from = from;
            this.month = month;
        }
    }

    /**
     * Config + live typed buffer for an on-screen keyboard page (see {@link #openKeyboardPage}) -
     * replaces the old "close menu, type in chat" flow so every input stays menu-only.
     */
    private static final class KeyboardSession {
        private final String title;
        private final List<String> keys;
        private final int columns;
        private final int maxLength;
        private final boolean uuidFormat;
        private final Consumer<String> onConfirm;
        private final Runnable onCancel;
        private final StringBuilder buffer = new StringBuilder();

        KeyboardSession(String title, List<String> keys, int columns, int maxLength, boolean uuidFormat,
                         Consumer<String> onConfirm, Runnable onCancel) {
            this.title = title;
            this.keys = keys;
            this.columns = columns;
            this.maxLength = maxLength;
            this.uuidFormat = uuidFormat;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
        }
    }

    private static final List<String> HEX_KEYS = List.of(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f");

    private final HistoryService historyService;
    private final TemplateDao templateDao;
    private final DupeAlertDao dupeAlertDao;
    private final PlayerDirectoryService playerDirectory;
    private final EventDao eventDao;
    private final LocationDao locationDao;
    private final ItemTrackingService itemTracking;
    /** Per-player state of the events page's hover item preview - see {@link #showEventPreview}. */
    private final Map<UUID, PreviewState> previewStates = new ConcurrentHashMap<>();
    /** DB-side preview info per unit UUID, cached for the current events page only (cleared
     * whenever the events page is rebuilt) so hovering back and forth doesn't re-query. */
    private final Map<String, UnitPreviewData> unitPreviewCache = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, SearchMode> awaitingSearchInput = new ConcurrentHashMap<>();
    private final Map<UUID, EventsFilter> eventsFilters = new ConcurrentHashMap<>();
    private final Map<UUID, KeyboardSession> keyboardSessions = new ConcurrentHashMap<>();
    private final Map<UUID, CalendarState> calendarSessions = new ConcurrentHashMap<>();
    /** Reopens whatever non-main page a player was last browsing (currently only ever the events
     * list) - per "když znovu otevřeš menu tak ti to otevře na samé stránce" (teleporting away via
     * an event's Teleport button leaves the menu's world-anchored entities behind; the next
     * {@code /purrlog gui} should resume there instead of jumping back to the main menu). Cleared
     * whenever {@link #openMainMenu} itself runs, so deliberately navigating "back" resets it. */
    private final Map<UUID, Runnable> lastPage = new ConcurrentHashMap<>();

    public AdminGuiService(HistoryService historyService, TemplateDao templateDao, DupeAlertDao dupeAlertDao,
                            PlayerDirectoryService playerDirectory, EventDao eventDao, LocationDao locationDao,
                            ItemTrackingService itemTracking, Plugin plugin, Logger logger) {
        this.locationDao = locationDao;
        this.itemTracking = itemTracking;
        this.historyService = historyService;
        this.templateDao = templateDao;
        this.dupeAlertDao = dupeAlertDao;
        this.playerDirectory = playerDirectory;
        this.eventDao = eventDao;
        this.plugin = plugin;
        this.logger = logger;
    }

    public void openMainMenu(Player player) {
        lastPage.remove(player.getUniqueId());
        // Single centered column, tight 0.35-block step, rel y -0.4..1.35 - straddles eye level
        // (0) and stays well short of the ~1.5-below-eye "feet" line (see the height-anchor note
        // in [[reference-purrtechdisplaygui-coordinate-rules]]) instead of sinking into the floor
        // like the first (too-deep, rel y up to 2.55) attempt did.
        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(navButton("players", cx(0), cy(-0.40), 0.04, "Hraci", e -> openPlayerList(player, 0)));
        buttons.add(navButton("search", cx(0), cy(-0.05), 0.04, "Hledat podle UUID", e -> openUuidSearchKeyboard(player)));
        buttons.add(navButton("alerts", cx(0), cy(0.30), 0.05, "Nevyresene alerty", e -> openAlerts(player)));
        buttons.add(navButton("close", cx(0), cy(0.65), 0.05, "Zavrit", e -> DisplayGuiAPI.closeMenu(player)));
        buttons.add(navButton("player-search", cx(0), cy(1.00), 0.05, "Hledat hrace", e -> beginPlayerSearch(player)));
        buttons.add(navButton("events", cx(0), cy(1.35), 0.05, "Vsechny udalosti", e -> openEventsPage(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:main", MENU_DISTANCE_PIXELS);
//        DisplayGuiAPI.closeMenu(player);
        DisplayGuiAPI.openMenu(player, screen);
    }

    /** Entry point for {@code /purrlog gui}: resumes whatever page the player was last on (e.g. the
     * events list, or a specific event's detail page) instead of always jumping back to the main
     * menu - per "když znovu otevřeš menu tak ti to otevře na samé stránce". Falls back to
     * {@link #openMainMenu} the first time, or once nothing is tracked (e.g. after a deliberate
     * "Hlavni menu" click). */
    public void openLastPageOrMain(Player player) {
        Runnable page = lastPage.get(player.getUniqueId());
        if (page != null) {
            page.run();
        } else {
            openMainMenu(player);
        }
    }

    private void beginPlayerSearch(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.PLAYER);
        // Menu stays open while typing in chat now - the DisplayGUI menu is world Display/
        // Interaction entities, not a vanilla inventory screen, so it never actually blocked chat
        // input; closing it here just made the menu visibly disappear during entry for no reason.
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
                player.sendMessage(mode == SearchMode.EVENTS_DATE ? "Zadavani zruseno." : "Hledani zruseno.");
                return;
            }
            switch (mode) {
                case PLAYER -> openPlayerSearch(player, raw);
                case EVENTS_DATE -> applyManualDateInput(player, raw);
                case KEYBOARD_MANUAL -> applyManualKeyboardInput(player, raw);
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
            historyLines.add(formatEventLine(e));
        }

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(iconButton(cx(-2.7), cy(-1.3), 0.05, icon));
        buttons.add(infoTextButton(cx(0.8), cy(-1.3), 0.05, infoLines));
        buttons.add(scrollListButton("history", cx(0), cy(0.1), 0.05, historyLines, "(zadna historie)"));
        buttons.add(navButton("back", cx(0), cy(1.4), -0.05, "Zpet", e -> openMainMenu(player)));

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
        buttons.add(scrollListButton("alerts", cx(0), cy(0.3), 0.05, lines, "(zadne nevyresene alerty)"));
        buttons.add(navButton("back", cx(0), cy(1.4), 0.05, "Zpet", e -> openMainMenu(player)));

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
        double y = -1.5;
        for (PlayerRecord p : page) {
            String status = p.online() ? "[ONLINE] " : "[OFFLINE] ";
            String suffix = p.online() ? "" : " (naposledy " + formatTime(p.lastSeenAt()) + ")";
            String label = status + p.currentName() + suffix;
            buttons.add(navButton("player-" + p.uuid(), cx(0), cy(y), 0.05, label, e -> openPlayerDetail(player, p.uuid())));
            y += 0.45;
        }

        List<String> infoLines = new ArrayList<>();
        infoLines.add(searchQuery != null ? "Vysledky hledani: '" + searchQuery + "'" : "Vsichni hraci");
        infoLines.add(all.isEmpty() ? "0 hracu" : (clampedOffset + 1) + "-" + end + " z " + all.size());
        buttons.add(infoTextButton(cx(0), cy(-2.1), 0.04, infoLines));

        if (clampedOffset > 0) {
            buttons.add(navButton("prev", cx(-1.2), cy(y + 0.3), 0.05, "Predchozi",
                    e -> openPlayerListPage(player, all, clampedOffset - PLAYERS_PAGE_SIZE, searchQuery)));
        }
        if (end < all.size()) {
            buttons.add(navButton("next", cx(1.2), cy(y + 0.3), 0.05, "Dalsi",
                    e -> openPlayerListPage(player, all, clampedOffset + PLAYERS_PAGE_SIZE, searchQuery)));
        }
        buttons.add(navButton("back", cx(0), cy(y + 0.6), 0.05, "Hlavni menu", e -> openMainMenu(player)));

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
            String tag = e.eventType().equals("CREATIVE_DUPLICATE") ? "DUPE-CREATIVE " : "";
            lines.add(new ActivityLine(e.timestamp(), tag + EventLineFormatter.formatLine(e)));
        }
        for (DupeAlertRecord a : activity.alerts()) {
            lines.add(new ActivityLine(a.detectedAt(),
                    "DUPE-BUG [" + EventLineFormatter.formatTime(a.detectedAt()) + "] [" + a.severity() + "] "
                            + a.note() + " uuid=" + a.unitUuid()));
        }
        List<String> activityLines = lines.stream()
                .sorted(Comparator.comparingLong(ActivityLine::timestamp).reversed())
                .map(ActivityLine::text)
                .toList();

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(infoTextButton(cx(0), cy(-1.3), 0.05, infoLines));
        buttons.add(scrollListButton("activity", cx(0), cy(0.3), 0.05, activityLines, "(zadna aktivita)"));
        buttons.add(navButton("back", cx(0), cy(1.4), -0.05, "Zpet", e -> openMainMenu(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:player:" + unit.uuid(), MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private PageData backgroundPage() {
        TextDisplayLayerData bg = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "bg", 1)
                .setText(List.of("PurrTechDetailLogger", "", "", ""))
                .setBackground(Color.fromARGB(100, 15, 15, 15))
                .setAutoFitText(true);

        bg.setScale(bg.getVectorWithPixels(30,30,0));
        bg.setWidth(SCREEN_WIDTH_BLOCKS);
        bg.setHeight(SCREEN_HEIGHT_BLOCKS);
        LayersData design = new LayersData(List.of(bg), GUI_PATH + ":background", GUI_PATH);
        // Center of the (SCREEN_WIDTH_BLOCKS x SCREEN_HEIGHT_BLOCKS) screen, NOT (0,0) - a PageData
        // at local (0,0) puts its own center on the top-left corner instead of screen-center, which
        // is exactly what caused the whole menu to render off to the player's left. See
        // [[reference-purrtechdisplaygui-coordinate-rules]].
        return new PageData(CENTER_X, CENTER_Y, 0, design, PageType.NORMAL);
    }

    /** Local x for a button placed {@code relX} blocks right (positive) or left (negative) of the
     * true screen center - see {@link #CENTER_X} and [[reference-purrtechdisplaygui-coordinate-rules]]. */
    private static double cx(double relX) {
        return CENTER_X + relX;
    }

    /** Local y for a button placed {@code relY} blocks below (positive) or above (negative) the
     * true screen center - see {@link #CENTER_Y} and [[reference-purrtechdisplaygui-coordinate-rules]]. */
    private static double cy(double relY) {
        return CENTER_Y + relY;
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick) {
        return navButton(id, x, y, z, label, onClick, false);
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick, boolean active) {
        // Interaction hitbox is auto-sized to the label's own rendered size (see buildTextButton)
        // instead of a hand-picked flat default - matches "design size" per the user's request.
        return buildTextButton(id, x, y, z, label, onClick, active, false);
    }

    private ButtonData compactButton(String id, double x, double y, double z, String label,
                                      Consumer<MenuButtonClickEvent> onClick, boolean active) {
        // Shrinks labels wider than COMPACT_TARGET_WIDTH_BLOCKS so every button in a packed grid
        // ends up roughly the same visual width regardless of label length - see
        // [[reference-purrtechdisplaygui-coordinate-rules]] for why the events-category grid needed
        // this (long labels at full scale vastly exceeded the grid's column step and stuck together).
        return buildTextButton(id, x, y, z, label, onClick, active, true);
    }

    /**
     * Shared text-button builder: sizes the Interaction hitbox from the text layer's own actual
     * rendered width/height ({@link TextDisplayLayerData#estimateContentWidthBlocks()} /
     * {@code estimateContentHeightBlocks()} - already scale-corrected, and these buttons never set
     * their own layer scale, so this is the true on-screen size) instead of a flat guessed default,
     * per the user's "velikost interact entit stejně velké jako design tlačítka" request. A small
     * padding keeps edge clicks registering; a floor keeps 1-2 character labels ("Vse") clickable.
     */
    private ButtonData buildTextButton(String id, double x, double y, double z, String label,
                                        Consumer<MenuButtonClickEvent> onClick, boolean active, boolean compact) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(List.of(label))
                .setBackground(active ? BUTTON_BACKGROUND_ACTIVE : BUTTON_BACKGROUND);
        if (compact) {
            // autoFitText stays false (the constructor default is true - explicitly turn it off) so
            // wrapLine() never splits this label onto a 2nd line no matter how long it is; width is
            // controlled entirely by the uniform scale computed below instead.
            text.setAutoFitText(false);
            double naturalWidthBlocks = text.estimateContentWidthBlocks();
            if (naturalWidthBlocks > COMPACT_TARGET_WIDTH_BLOCKS) {
                float scale = (float) Math.max(COMPACT_MIN_TEXT_SCALE,
                        COMPACT_TARGET_WIDTH_BLOCKS / naturalWidthBlocks);
                text.setScale(new Vector3f(scale, scale, 1f));
            }
        }
        double widthPixels = Math.max(HITBOX_MIN_WIDTH_PX,
                text.estimateContentWidthBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
        double heightPixels = HITBOX_HEIGHT_PX;
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                .hitboxOffsetZ(hitboxRecessZ(widthPixels))
                .build();
    }

    /** A single calendar day cell - same auto-width sizing as {@link #buildTextButton}, but with
     * its own {@code valid}/disabled background instead of the active/inactive one (a day is never
     * "active", it's either pickable or not - see {@link #isValidEventsDate}). */
    private ButtonData calendarDayButton(String id, double x, double y, double z, int day, boolean valid,
                                          Consumer<MenuButtonClickEvent> onClick) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(List.of(String.valueOf(day)))
                .setBackground(valid ? BUTTON_BACKGROUND : BUTTON_BACKGROUND_DISABLED);
        double widthPixels = Math.max(HITBOX_MIN_WIDTH_PX,
                text.estimateContentWidthBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, HITBOX_HEIGHT_PX)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                .hitboxOffsetZ(hitboxRecessZ(widthPixels))
                .build();
    }

    /**
     * @param widthPixels  explicit hitbox size in pixels (16px = 1 block) - pick this so the
     *                     resulting block size is comfortably smaller than whatever grid step this
     *                     button sits on; the hitbox does NOT auto-shrink to the label's visual
     *                     size. See [[reference-purrtechdisplaygui-coordinate-rules]].
     */
    private ButtonData navButton(String id, double x, double y, double z, double widthPixels, double heightPixels,
                                  String label, Consumer<MenuButtonClickEvent> onClick) {
        return navButton(id, x, y, z, widthPixels, heightPixels, label, onClick, false);
    }

    private ButtonData navButton(String id, double x, double y, double z, double widthPixels, double heightPixels,
                                  String label, Consumer<MenuButtonClickEvent> onClick, boolean active) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(List.of(label))
                .setBackground(active ? BUTTON_BACKGROUND_ACTIVE : BUTTON_BACKGROUND);
        LayersData design = new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                .hitboxOffsetZ(hitboxRecessZ(widthPixels))
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
                .hitboxOffsetZ(hitboxRecessZ(30))
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
                .hitboxOffsetZ(hitboxRecessZ(10))
                .build();
    }

    private ButtonData scrollListButton(String id, double x, double y, double z, List<String> items, String emptyLabel) {
        return scrollListButton(id, x, y, z, 20, 130, 4, items, emptyLabel);
    }

    private ButtonData scrollListButton(String id, double x, double y, double z, double widthPixels,
                                         double heightPixels, int visibleRows, List<String> items, String emptyLabel) {
        TextDisplayLayerData content = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-content", 1)
                .setBackground(PANEL_BACKGROUND);
        LayersData design = new LayersData(List.of(content), GUI_PATH + ":" + id, GUI_PATH);
        return ScrollButtonData.scrollButtonBuilder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(design)
                .id(id)
                .contentLayer(1)
                .visibleRows(visibleRows)
                .items(items.isEmpty() ? List.of(emptyLabel) : items)
                .hitboxOffsetZ(hitboxRecessZ(widthPixels))
                .build();
    }

    /** How far (pixels, negative = backward/away from the viewer) to push a button's Interaction
     * hitbox via {@link eu.purrtech.displaygui.API.data.buttonData.ButtonDataBuilder#hitboxOffsetZ}.
     * Bukkit's {@code Interaction} entity only has a single {@code width} for BOTH its horizontal
     * axes ({@code Button.createButton} calls {@code setInteractionWidth} once, used for both X and
     * Z) - the hitbox is always a {@code width x height x width} box centered on its spawn point,
     * so a wide button's box juts forward by {@code width/2} toward the player by default, however
     * thin its actual visual layer is. Recessing by exactly half the width makes the box's forward
     * face land flush with the button's own visual plane (no forward overshoot) while the rest of
     * the box simply extends backward, which is harmless - see the user's own framing: "nevadí to
     * že ten hitbox je vždy čtverec [ale] tím snížíš to vystoupnutí z toho menučka". See
     * [[reference-purrtechdisplaygui-coordinate-rules]]. */
    private static double hitboxRecessZ(double widthPixels) {
        return -widthPixels / 2.0;
    }

    /** {@link #hitboxRecessZ} counterpart for buttons whose layer is ROTATED: DisplayGUI splits
     * those into ~0.5-block Interaction tiles laid along the tilted face (Button#computeHitboxCells,
     * PREFERRED_HITBOX_TILE_SIZE), so each tile only juts forward by half a tile, not half the
     * whole button - recessing by the button's half width pushed the tiles far behind the layer
     * ("dej ty hitboxy blíže k té vrstvě"). Half a tile puts each tile's front face on the layer. */
    private static final double ROTATED_TILE_HITBOX_RECESS_Z = -(0.5 * PIXELS_PER_BLOCK) / 2.0;

    private static String formatTime(Long epochMillis) {
        return EventLineFormatter.formatTime(epochMillis);
    }

    /** Best-effort "name (uuid)" for a history line - falls back to the raw UUID if the profile
     * can't be resolved (e.g. never cached, offline-mode server). */
    private static String resolvePlayerAlias(String playerUuidString) {
        UUID playerUuid = UUID.fromString(playerUuidString);
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        Player onlinePlayer = offlinePlayer.getPlayer();
        String name = onlinePlayer != null ? onlinePlayer.getName() : offlinePlayer.getPlayerProfile().getName();
        return (name != null ? name : playerUuidString);
    }

    private static String formatEventLine(EventRecord e) {
        String line = EventLineFormatter.formatLine(e);
        if (e.playerUuid() != null) {
            line = line + " hrac=" + resolvePlayerAlias(e.playerUuid());
        }
        return line;
    }

    // === Events browser: all events, newest first, filterable by category and time range. ===

    public void openEventsPage(Player player) {
        lastPage.put(player.getUniqueId(), () -> openEventsPage(player));
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        List<String> types = filter.activeCategory != null ? List.of(filter.activeCategory) : List.of();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<EventRecord> events = eventDao.findFiltered(types, filter.from, filter.to, EVENTS_LIST_LIMIT);
                // Row icons - one batched lookup instead of one query per row.
                Map<String, String> materials = templateDao.findMaterialsByUnits(events.stream()
                        .map(EventRecord::unitUuid).filter(java.util.Objects::nonNull).distinct().toList());
                Bukkit.getScheduler().runTask(plugin, () -> openEventsListPage(player, filter, events, materials));
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni udalosti selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openEventsListPage(Player player, EventsFilter filter, List<EventRecord> events,
                                    Map<String, String> materials) {
        List<ButtonData> buttons = new ArrayList<>();
        resetEventPreview(player);
        unitPreviewCache.clear();

        List<String> categoryButtons = new ArrayList<>();
        categoryButtons.add("ALL");
        categoryButtons.addAll(EVENT_CATEGORIES);

        // Filter column widened to 2 columns (was 3x4 at a too-tight 1.15 step, which made 6-8
        // char labels visually stick together at full scale) x 6 rows, freeing the horizontal room
        // a 3rd column would need. Combined with compactButton's shrink, every label now fits
        // comfortably under the 2.0-block column step - see
        // [[reference-purrtechdisplaygui-coordinate-rules]]. Stretched down/left into the space
        // freed by moving the date range off this row entirely, per the user's own "roztáhnou
        // dolu... nebo doleva" instruction.
        int columns = 2;
        double stepX = 2.0;
        double filterCenterX = -2.5;
        double startX = filterCenterX - (columns - 1) * stepX / 2.0;
        double startY = -1.3;
        double stepY = 0.4;
        double lastRowY = startY;
        for (int i = 0; i < categoryButtons.size(); i++) {
            String type = categoryButtons.get(i);
            double x = startX + (i % columns) * stepX;
            double y = startY + (i / columns) * stepY;
            lastRowY = Math.max(lastRowY, y);
            boolean active = type.equals("ALL") ? filter.activeCategory == null : type.equals(filter.activeCategory);
            String label = type.equals("ALL") ? "Vse" : CATEGORY_LABELS.getOrDefault(type, type);
            buttons.add(compactButton("cat-" + type, cx(x), cy(y), 0.05, label, e -> toggleCategory(player, type), active));
        }

        // Events list - "Použij na ty události button_list_scroll": one real clickable ButtonData
        // per event (unchanged from before), but now every matching event (up to EVENTS_LIST_LIMIT)
        // is handed to a single ButtonListScrollButtonData instead of a manually paginated 5-per-page
        // slice with prev/next buttons - the player scrolls the list with the mouse wheel. Same
        // right-of-center column position as before ("dej to více doprava a níže"). See
        // [[reference-purrtechdisplaygui-coordinate-rules]].
        // Was 2.6 - moved 0.75 right ("posuň to tak třeba o 0.5-1.0 doprava") to make room for the
        // item icon now drawn on each row's left side.
        double columnX = 3.35;
        // Was -0.2 - raised so the bottom row no longer clips into the ground ("jedna ta událost
        // dole se buguje do země").
        double rowStartY = -1.0;
        double rowStepY = 0.65;
        // The list's own .at() anchor is documented to be the BOTTOM row (row 0/topmost sits above
        // it, built up by rowSpacingBlocks per row) - see eventsListButton - so this is
        // rowStartY shifted down to where the bottom-most visible row sits.
        double bottomRowY = rowStartY + (EVENTS_VISIBLE_ROWS - 1) * rowStepY;
        buttons.add(eventsListButton(player, cx(columnX), cy(bottomRowY), EVENTS_LIST_Z, events, materials, filter, rowStepY));

        // Hover preview panel, right of the list ("Ukazuj ty itemy vpravo") - starts blank, filled
        // in live by showEventPreview when a row is hovered. Bottom-anchored at the list's bottom
        // row so it grows upward alongside the list.
        buttons.add(eventPreviewButton(player, cx(columnX + EVENT_PREVIEW_OFFSET_X), cy(bottomRowY), EVENT_PREVIEW_Z));

        // Info panel sits directly under the filter column now that the date row moved out - per
        // "vlevo ty filtr tlačítka... dej jim tam více prostoru".
        List<String> infoLines = new ArrayList<>();
        infoLines.add("Kategorie: " + (filter.activeCategory != null
                ? CATEGORY_LABELS.getOrDefault(filter.activeCategory, filter.activeCategory) : "vsechny"));
        infoLines.add("Zaznamu: " + events.size() + (events.size() >= EVENTS_LIST_LIMIT ? "+" : ""));
        buttons.add(infoTextButton(cx(filterCenterX), cy(lastRowY + 0.4), 0.04, infoLines));

        // Time-display-mode toggle ("určí si hráč ve filtru") + date range, stacked vertically in
        // the center column - opens the small in-game calendar instead of a chat prompt.
        buttons.add(navButton("time-mode", cx(0), cy(0.1), 0.05,
                filter.relativeTime ? "Cas: pred X" : "Cas: datum", e -> {
                    filter.relativeTime = !filter.relativeTime;
                    openEventsPage(player);
                }));
        double dateY = 0.5;
        double dateStepY = 0.35;
        buttons.add(navButton("date-from", cx(0), cy(dateY), 0.05,
                "Od: " + (filter.from != null ? formatTime(filter.from) : "-"), e -> openEventsCalendar(player, true)));
        buttons.add(navButton("date-to", cx(0), cy(dateY + dateStepY), 0.05,
                "Do: " + (filter.to != null ? formatTime(filter.to) : "-"), e -> openEventsCalendar(player, false)));
        double afterDateY = dateY + dateStepY;
        if (filter.from != null || filter.to != null) {
            afterDateY += dateStepY;
            buttons.add(navButton("date-clear", cx(0), cy(afterDateY), 0.05, "Zrusit rozsah", e -> {
                filter.from = null;
                filter.to = null;
                openEventsPage(player);
            }));
        }

        buttons.add(navButton("back", cx(0), cy(afterDateY + 0.25), 0.05, "Hlavni menu", e -> openMainMenu(player)));

        String pageKey = "purrtechlog:events:" + (filter.activeCategory != null ? filter.activeCategory : "all");
        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, pageKey, MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private void toggleCategory(Player player, String type) {
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        filter.activeCategory = (type.equals("ALL") || type.equals(filter.activeCategory)) ? null : type;
        openEventsPage(player);
    }

    /** Target width for a two-line event-row button (see {@link #eventRowButton}) - shrinks the
     * text (never below {@link #COMPACT_MIN_TEXT_SCALE}), same idea as {@link #compactButton}, so
     * a long "#id  time  Player: name - action" line 1 can't grow wide enough to spill into the
     * filter column to its left. */
    // Was 3.6 - the rows' left side reached into the center column's buttons ("zmenši tu šířku
    // těch tlačítek... hlavně levou část").
    private static final double EVENTS_ROW_TARGET_WIDTH_BLOCKS = 3.0;

    /**
     * A single clickable event-list row: two real lines of text (unlike {@link #buildTextButton}'s
     * always-single-line label), auto-sized off the text layer's own actual rendered width/height -
     * safe here since both lines are genuine authored content, not a wrapped single line (see
     * [[reference-purrtechdisplaygui-coordinate-rules]] re {@code estimateContentHeightBlocks()}).
     * Rotated by {@link #EVENTS_LIST_ROTATION_Y_DEGREES} to angle back toward the player, since this
     * column sits well off to the right of screen center ("natoč to na hráče").
     */
    private ButtonData eventRowButton(String id, double x, double y, double z, List<String> lines, ItemStack icon,
                                       Consumer<MenuButtonClickEvent> onClick,
                                       Consumer<MenuActionContext> onHoverStart, Consumer<MenuActionContext> onHoverEnd) {
        // Text shifted right by half an icon so text + icon together are centered on the button -
        // the (always centered) hitbox then only needs to be text + icon wide.
        TextDisplayLayerData text = new TextDisplayLayerData(EVENT_ROW_ICON_BLOCKS / 2.0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(lines)
                .setBackground(BUTTON_BACKGROUND);
        text.setAutoFitText(false);
        double naturalWidthBlocks = text.estimateContentWidthBlocks();
        if (naturalWidthBlocks > EVENTS_ROW_TARGET_WIDTH_BLOCKS) {
            float scale = (float) Math.max(COMPACT_MIN_TEXT_SCALE, EVENTS_ROW_TARGET_WIDTH_BLOCKS / naturalWidthBlocks);
            text.setScale(new Vector3f(scale, scale, 1f));
        }
        // setRotationY returns the base DisplayLayerData type, not TextDisplayLayerData - called as
        // its own statement (not chained) so `text` below still resolves to the
        // TextDisplayLayerData-only estimate*Blocks() methods. See
        // [[reference-purrtechdisplaygui-coordinate-rules]].
        text.setRotationY(EVENTS_LIST_ROTATION_Y_DEGREES);

        double textWidthBlocks = text.estimateContentWidthBlocks();
        double textHeightBlocks = text.estimateContentHeightBlocks();

        // Item icon on the row's left ("na levou stranu toho ještě přidej ten item"), vertically
        // centered on the text - a text display grows upward from its anchor while an item display
        // is centered on it, hence the -height/2 (this GUI's local +y = down).
        ItemDisplayLayerData iconLayer = new ItemDisplayLayerData(
                -textWidthBlocks / 2.0 + EVENT_ROW_ICON_PULL_IN_BLOCKS, -textHeightBlocks / 2.0, 0,
                GUI_PATH, id + "-icon", 2)
                .setItemStack(icon);
        iconLayer.setScale(new Vector3f((float) EVENT_ROW_ICON_BLOCKS, (float) EVENT_ROW_ICON_BLOCKS, (float) EVENT_ROW_ICON_BLOCKS));
        iconLayer.setRotationY(EVENTS_LIST_ROTATION_Y_DEGREES);

        double widthPixels = Math.max(HITBOX_MIN_WIDTH_PX,
                (textWidthBlocks + EVENT_ROW_ICON_BLOCKS) * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
        double heightPixels = Math.max(HITBOX_HEIGHT_PX,
                textHeightBlocks * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
        LayersData design = new LayersData(List.of(text, iconLayer), GUI_PATH + ":" + id, GUI_PATH);
        // Not hitboxRecessZ (half the whole width): these rows live inside eventsListButton, whose
        // own scroll hitbox sits behind them, and a row recessed that far would end up behind it and
        // never get hovered/clicked. The rows are rotated, so their hitbox is 0.5-block tiles along
        // the tilted face - recessing by half a tile keeps them on the layer while the list's
        // built-in 0.5-block row offset still keeps them in front of the scroll hitbox.
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                .onHoverStart(onHoverStart)
                .onHoverEnd(onHoverEnd)
                .hitboxOffsetZ(ROTATED_TILE_HITBOX_RECESS_Z)
                .build();
    }

    /** How much narrower (px) the scroll hitbox is than the list's visual width - kept narrower
     * because an Interaction's width is also its depth, so a full-width box juts far out toward the
     * player. 3 hitbox tiles of 0.5 block each = 1.5 blocks = 24 px ("jenom o 3 ty kostky"). */
    private static final double EVENTS_LIST_SCROLL_HITBOX_SHRINK_PX = 3 * 0.5 * PIXELS_PER_BLOCK;

    /**
     * The events list, as a single {@code ButtonType.BUTTON_LIST_SCROLL} - per "Použij na ty
     * události button_list_scroll". Every matching event (up to {@link #EVENTS_LIST_LIMIT}) becomes
     * one {@link #eventRowButton} row handed to {@link ButtonListScrollButtonData}; only
     * {@link #EVENTS_VISIBLE_ROWS} are shown at once and the player scrolls the rest with the mouse
     * wheel, replacing the old 5-per-page prev/next buttons entirely.
     * <p>
     * Per {@code ButtonListScrollButtonData}'s own contract, each row's x/y/z is ignored - the list
     * computes row position itself from {@code rowSpacingBlocks} and the scroll offset - so
     * {@link #eventRowButton} is called with placeholder (0, 0, small z) coordinates below. This
     * button's own {@code x,y} ({@code at(x, y, z)} here) is the anchor for the BOTTOM-most visible
     * row (row 0/topmost is built up from there), matching {@code ButtonListScrollButton.rowLocation}
     * in the real DisplayGUI source - see [[reference-purrtechdisplaygui-coordinate-rules]].
     */
    private ButtonData eventsListButton(Player player, double x, double y, double z, List<EventRecord> events,
                                         Map<String, String> materials, EventsFilter filter, double rowSpacingBlocks) {
        List<ButtonData> rows = new ArrayList<>();
        for (EventRecord ev : events) {
            // Shulker sessions carry the shulker's own color/material in their detail - a placed
            // shulker has no unit, and a held one's anchor template is just plain SHULKER_BOX.
            String materialName = ShulkerSessionLog.EVENT_TYPE.equals(ev.eventType())
                    ? ShulkerSessionLog.shulkerMaterial(ev.detail())
                    : ev.unitUuid() != null ? materials.get(ev.unitUuid()) : null;
            ItemStack icon = iconFor(materialName);
            rows.add(eventRowButton("event-" + ev.id(), 0, 0, 0.01,
                    eventRowLines(ev, filter.relativeTime), icon, e -> openEventDetail(player, ev.id()),
                    ctx -> showEventPreview(player, ev, icon),
                    ctx -> scheduleEventPreviewHide(player)));
        }

        double widthPixels = (EVENTS_ROW_TARGET_WIDTH_BLOCKS + EVENT_ROW_ICON_BLOCKS) * PIXELS_PER_BLOCK + HITBOX_PADDING_PX;
        double heightPixels = EVENTS_VISIBLE_ROWS * rowSpacingBlocks * PIXELS_PER_BLOCK;

        // Frame/background panel spans the whole visible column, vertically centered on it - since
        // this button's own x,y anchors the BOTTOM row (see javadoc above), the frame sits
        // half the column's height ABOVE that anchor, i.e. a negative local y in this GUI's
        // "+y = down" convention (see cy()). No real text content to auto-fit to (this is a blank
        // backdrop, not a label), so width/height/scale are set explicitly instead of relying on
        // TextDisplayLayerData's normal auto-fit-to-text sizing - same technique as the real
        // DisplayGUI source's own list-frame example (Internal.commands.TestCommand, "playerlist").
        TextDisplayLayerData frame = new TextDisplayLayerData(
                0, -(EVENTS_VISIBLE_ROWS * rowSpacingBlocks) / 2.0, 0, GUI_PATH, "events-list-frame", 0)
                .setText(List.of(" "))
                .setBackground(PANEL_BACKGROUND);
        frame.setAutoFitText(false);
        frame.setScale(frame.getVectorWithPixels(widthPixels, heightPixels, 1));
        frame.setWidth((float) (widthPixels / PIXELS_PER_BLOCK));
        frame.setHeight((float) (heightPixels / PIXELS_PER_BLOCK));
        LayersData frameLayers = new LayersData(List.of(frame), GUI_PATH + ":events-list", GUI_PATH);

        // Scroll hitbox: narrower than the frame and recessed so its front face is flush with the
        // list plane - the (unrecessed) row hitboxes then sit fully in front of it.
        double scrollHitboxWidthPixels = Math.max(HITBOX_MIN_WIDTH_PX, widthPixels - EVENTS_LIST_SCROLL_HITBOX_SHRINK_PX);
        return ButtonListScrollButtonData.buttonListScrollBuilder()
                .at(x, y, z)
                .size(scrollHitboxWidthPixels, heightPixels)
                .hitboxOffsetZ(hitboxRecessZ(scrollHitboxWidthPixels))
                .layers(frameLayers)
                .id("events-list")
                .items(rows)
                .visibleRows(EVENTS_VISIBLE_ROWS)
                .rowSpacing(rowSpacingBlocks)
                .build();
    }

    /** Row text for {@link #eventRowButton}: line 1 = ID (left-most - "ID se bude ukazovat na levé
     * straně aby se to odlišilo") + time (absolute or "pred X", per {@link EventsFilter#relativeTime})
     * + "Player: name" + what happened; line 2 = world + coords, or a placeholder when the event
     * carries no location. */
    private List<String> eventRowLines(EventRecord e, boolean relativeTime) {
        String time = relativeTime ? EventLineFormatter.formatTimeAgo(e.timestamp()) : formatTime(e.timestamp());
        String who = e.playerUuid() != null ? resolvePlayerAlias(e.playerUuid()) : "?";
        String action = CATEGORY_LABELS.getOrDefault(e.eventType(), e.eventType());
        String line1 = "#" + e.id() + "  " + time + "  Player: " + who + " - " + action;
        String line2 = e.world() != null
                ? "world: " + e.world() + " " + e.x() + " " + e.y() + " " + e.z()
                : "world: neznamy";
        return List.of(line1, line2);
    }

    // === Events page hover item preview - hovering a row shows the item it's about in a panel
    // right of the list ("Ukazuj ty itemy vpravo"); it lingers 2 s after leaving the row so the
    // player can move onto it, and clicking it opens that item's detail + full history. ===

    /** Rendered size (blocks) of each row's item icon - see {@link #eventRowButton}. */
    private static final double EVENT_ROW_ICON_BLOCKS = 0.45;
    /** How far (blocks) each row's icon is pulled right, toward its text ("dát spíše blíže k tomu
     * seznamu"). 0 = icon edge exactly on the ESTIMATED text edge; the estimate runs a bit wide,
     * which left a visible gap. Bigger = closer; too big and the icon overlaps the text. */
    private static final double EVENT_ROW_ICON_PULL_IN_BLOCKS = 0.2;
    private static final String EVENT_PREVIEW_ID = "event-preview";
    /** Preview panel center, relative to the list column's center: half the widest row
     * (text + icon), plus a small gap, plus half the panel. Not yet confirmed in-game. */
    // Was 3.8 - moved closer to the list ("dej více blíže"); the stronger tilt below also narrows
    // how wide the panel looks, so it fits closer without touching the rows.
    private static final double EVENT_PREVIEW_OFFSET_X = 3.0;
    private static final double EVENT_PREVIEW_WIDTH_BLOCKS = 2.8;
    private static final double EVENT_PREVIEW_HEIGHT_BLOCKS = 3.0;
    private static final double EVENT_PREVIEW_ITEM_BLOCKS = 0.7;
    /** Angled back toward the player more than the list, since it sits even further right. */
    private static final float EVENT_PREVIEW_ROTATION_Y_DEGREES = -55f;
    /** Depth (blocks, + = toward the player). Was 0.05, same plane as the list - the panel then
     * looked like it sat behind the list ("je pořád z pohledu hráče za tím listem"), so it's
     * pulled forward to sit beside it instead. */
    // 1.0 -> 3.5 ("třeba 3 až 5 to Z"). The menu itself is MENU_DISTANCE_PIXELS = 5 blocks away,
    // so 5 would put the panel level with the player.
    private static final double EVENT_PREVIEW_Z = 3.5;
    /** "tam bude ještě 2 sekundy a pak to zmizne" */
    private static final long EVENT_PREVIEW_LINGER_TICKS = 40;
    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    private static final class PreviewState {
        /** Event id whose preview is shown (or loading), null = panel blank. */
        private String shownKey;
        /** Unit behind the shown preview, null = nothing clickable (blank or no-item barrier). */
        private String unitUuid;
        private boolean hoveringPreview;
        /** Shown event is a shulker session - clicking opens its contents grid instead. */
        private boolean shulkerEvent;
        private BukkitTask hideTask;
    }

    /** DB-side facts for a unit's preview; {@code unit} null = UUID not found in the DB. */
    private record UnitPreviewData(TrackedUnitRecord unit, String material, UnitLocationRecord location,
                                   int eventCount) {
    }

    private static ItemStack iconFor(String materialName) {
        Material material = materialName != null ? Material.matchMaterial(materialName) : null;
        return new ItemStack(material != null && material.isItem() ? material : Material.BARRIER);
    }

    /** The (initially blank) preview panel - see {@link #showEventPreview}. */
    private ButtonData eventPreviewButton(Player player, double x, double y, double z) {
        double widthPixels = EVENT_PREVIEW_WIDTH_BLOCKS * PIXELS_PER_BLOCK;
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, EVENT_PREVIEW_HEIGHT_BLOCKS * PIXELS_PER_BLOCK)
                .layers(eventPreviewDesign(null, List.of(" "), TRANSPARENT))
                .id(EVENT_PREVIEW_ID)
                .onLeftClick(e -> openPreviewedItem(player))
                .onHoverStart(ctx -> {
                    PreviewState state = previewStates.computeIfAbsent(player.getUniqueId(), id -> new PreviewState());
                    state.hoveringPreview = true;
                    cancelEventPreviewHide(state);
                })
                .onHoverEnd(ctx -> {
                    PreviewState state = previewStates.get(player.getUniqueId());
                    if (state != null) {
                        state.hoveringPreview = false;
                        scheduleEventPreviewHide(player, state);
                    }
                })
                .hitboxOffsetZ(ROTATED_TILE_HITBOX_RECESS_Z)
                .build();
    }

    /** Text (position 1) with the item (position 2) above it. Always the same two positions so
     * {@link Button#updateButton(ButtonData)} can swap content in place without respawning. */
    private LayersData eventPreviewDesign(ItemStack item, List<String> lines, Color background) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, EVENT_PREVIEW_ID + "-text", 1)
                .setText(lines)
                .setBackground(background);
        text.setAutoFitText(false);
        double naturalWidthBlocks = text.estimateContentWidthBlocks();
        if (naturalWidthBlocks > EVENT_PREVIEW_WIDTH_BLOCKS) {
            float scale = (float) Math.max(COMPACT_MIN_TEXT_SCALE, EVENT_PREVIEW_WIDTH_BLOCKS / naturalWidthBlocks);
            text.setScale(new Vector3f(scale, scale, 1f));
        }
        text.setRotationY(EVENT_PREVIEW_ROTATION_Y_DEGREES);
        double textHeightBlocks = text.estimateContentHeightBlocks();

        ItemDisplayLayerData itemLayer = new ItemDisplayLayerData(
                0, -(textHeightBlocks + EVENT_PREVIEW_ITEM_BLOCKS / 2.0 + 0.1), 0,
                GUI_PATH, EVENT_PREVIEW_ID + "-item", 2)
                .setItemStack(item != null ? item : new ItemStack(Material.AIR));
        float itemScale = (float) EVENT_PREVIEW_ITEM_BLOCKS;
        itemLayer.setScale(new Vector3f(itemScale, itemScale, itemScale));
        itemLayer.setRotationY(EVENT_PREVIEW_ROTATION_Y_DEGREES);
        return new LayersData(List.of(text, itemLayer), GUI_PATH + ":" + EVENT_PREVIEW_ID, GUI_PATH);
    }

    /** Row hover start: show this event's item right away, replacing whatever was shown. */
    private void showEventPreview(Player player, EventRecord ev, ItemStack rowIcon) {
        PreviewState state = previewStates.computeIfAbsent(player.getUniqueId(), id -> new PreviewState());
        cancelEventPreviewHide(state);
        String key = String.valueOf(ev.id());
        if (key.equals(state.shownKey)) {
            return;
        }
        state.shownKey = key;
        state.unitUuid = ev.unitUuid();
        state.shulkerEvent = ShulkerSessionLog.EVENT_TYPE.equals(ev.eventType());

        if (state.shulkerEvent) {
            ShulkerSessionLog.Session session = ShulkerSessionLog.parse(ev.detail());
            List<String> lines = new ArrayList<>();
            lines.add("Shulker #" + ev.id());
            if (session != null) {
                List<ShulkerSessionLog.SlotDiff> diffs = ShulkerSessionLog.diff(session.before(), session.after());
                lines.add(ShulkerSessionLog.MODE_HELD.equals(session.mode()) ? "Otevren v ruce" : "Polozeny shulker");
                lines.add("Kde: " + (ev.world() != null ? ev.world() + " " + ev.x() + " " + ev.y() + " " + ev.z() : "?"));
                lines.add("Pridal: " + ShulkerSessionLog.totalAdded(diffs) + " ks");
                lines.add("Vzal: " + ShulkerSessionLog.totalRemoved(diffs) + " ks");
            }
            lines.add("> Klikni pro obsah shulkeru");
            applyEventPreview(player, rowIcon, lines, PANEL_BACKGROUND);
            return;
        }

        if (ev.unitUuid() == null) {
            applyEventPreview(player, new ItemStack(Material.BARRIER),
                    List.of("Udalost #" + ev.id(), "Tento event neobsahuje", "zadny item."), PANEL_BACKGROUND);
            return;
        }
        String unitUuid = ev.unitUuid();
        UnitPreviewData cached = unitPreviewCache.get(unitUuid);
        if (cached != null) {
            renderUnitPreview(player, unitUuid, cached, rowIcon);
            return;
        }
        applyEventPreview(player, rowIcon, List.of("Nacitam...", unitUuid), PANEL_BACKGROUND);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<HistoryService.UnitHistory> history = historyService.lookup(unitUuid);
                TrackedUnitRecord unit = history.map(HistoryService.UnitHistory::unit).orElse(null);
                String material = unit != null ? templateDao.findMaterialById(unit.templateId()) : null;
                UnitLocationRecord location = locationDao.findByUnit(unitUuid);
                int eventCount = history.map(h -> h.events().size()).orElse(0);
                UnitPreviewData data = new UnitPreviewData(unit, material, location, eventCount);
                unitPreviewCache.put(unitUuid, data);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (key.equals(state.shownKey)) { // player may have moved on meanwhile
                        renderUnitPreview(player, unitUuid, data, rowIcon);
                    }
                });
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni nahledu itemu selhalo: " + e);
            }
        });
    }

    private void renderUnitPreview(Player player, String unitUuid, UnitPreviewData data, ItemStack rowIcon) {
        // Amount/enchants/name aren't stored in the DB - they're read off the real item wherever
        // it last was, if that's currently loaded (online player / loaded chunk).
        ItemStack live = findLiveItem(unitUuid, data.location());
        List<String> lines = new ArrayList<>();
        lines.add(itemTitle(live, data.material()));
        lines.add("UUID: " + unitUuid);
        lines.add("Material: " + (data.material() != null ? data.material() : "?"));
        if (live != null) {
            lines.add("Mnozstvi: " + live.getAmount() + " ks ve stacku");
            lines.add("Enchanty: " + formatEnchants(live));
        } else {
            lines.add("Mnozstvi/enchanty: ? (item neni nacteny)");
        }
        TrackedUnitRecord unit = data.unit();
        if (unit != null) {
            lines.add("Stav: " + (unit.alive() ? "existuje" : "znicen (" + unit.destroyedCause() + ")"));
            lines.add("Vznik: " + formatTime(unit.genesisAt()) + " (" + unit.origin() + ")");
        } else {
            lines.add("Stav: neni v DB");
        }
        lines.add("Poloha: " + formatUnitLocation(data.location()));
        lines.add("Udalosti: " + data.eventCount());
        lines.add("> Klikni pro detail a historii");
        applyEventPreview(player, live != null ? live : rowIcon, lines, PANEL_BACKGROUND);
    }

    private static String itemTitle(ItemStack live, String material) {
        if (live != null) {
            ItemMeta meta = live.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            }
            return live.getType().name();
        }
        return material != null ? material : "Neznamy item";
    }

    private static String formatEnchants(ItemStack item) {
        Map<Enchantment, Integer> enchants = new HashMap<>(item.getEnchantments());
        if (item.getItemMeta() instanceof EnchantmentStorageMeta stored) {
            enchants.putAll(stored.getStoredEnchants());
        }
        if (enchants.isEmpty()) {
            return "zadne";
        }
        List<String> parts = new ArrayList<>();
        enchants.forEach((enchant, level) -> parts.add(enchant.getKey().getKey() + " " + level));
        return String.join(", ", parts);
    }

    private static String formatUnitLocation(UnitLocationRecord loc) {
        if (loc == null) {
            return "neznama";
        }
        String coords = loc.world() != null ? loc.world() + " " + loc.x() + " " + loc.y() + " " + loc.z() : "";
        String slot = loc.slot() != null ? " slot " + loc.slot() : "";
        return switch (loc.locationType()) {
            case "PLAYER_INVENTORY" -> "inventar " + resolvePlayerAlias(loc.playerUuid()) + slot;
            case "ENDER_CHEST" -> "ender chest " + resolvePlayerAlias(loc.playerUuid()) + slot;
            case "BLOCK_CONTAINER" -> (loc.containerType() != null ? loc.containerType() : "kontejner") + " " + coords + slot;
            case "GROUND" -> "na zemi " + coords;
            case "PLACED_BLOCK" -> "polozeny blok " + coords;
            case "PLACED_INTO_MENU" -> "menu " + loc.menuName() + slot;
            default -> loc.locationType() + (coords.isEmpty() ? "" : " " + coords) + slot;
        };
    }

    /** The real item at the unit's last known location, only if it's loaded right now and still
     * carries this unit's UUID; never loads a chunk. Null otherwise. Main thread only. */
    private ItemStack findLiveItem(String unitUuid, UnitLocationRecord loc) {
        if (loc == null || loc.slot() == null) {
            return null;
        }
        Inventory inventory = switch (loc.locationType()) {
            case "PLAYER_INVENTORY", "ENDER_CHEST" -> {
                Player owner = loc.playerUuid() != null ? Bukkit.getPlayer(UUID.fromString(loc.playerUuid())) : null;
                if (owner == null) {
                    yield null;
                }
                yield loc.locationType().equals("ENDER_CHEST") ? owner.getEnderChest() : owner.getInventory();
            }
            case "BLOCK_CONTAINER" -> {
                World world = loc.world() != null ? Bukkit.getWorld(loc.world()) : null;
                if (world == null || loc.x() == null || loc.y() == null || loc.z() == null
                        || !world.isChunkLoaded(loc.x() >> 4, loc.z() >> 4)) {
                    yield null;
                }
                BlockState blockState = world.getBlockAt(loc.x(), loc.y(), loc.z()).getState(false);
                yield blockState instanceof Container container ? container.getInventory() : null;
            }
            default -> null;
        };
        if (inventory == null || loc.slot() < 0 || loc.slot() >= inventory.getSize()) {
            return null;
        }
        ItemStack item = inventory.getItem(loc.slot());
        if (item == null || item.getType().isAir()
                || !itemTracking.readAllUnits(item).contains(UUID.fromString(unitUuid))) {
            return null;
        }
        return item.clone();
    }

    private void applyEventPreview(Player player, ItemStack item, List<String> lines, Color background) {
        Button preview = findEventPreviewButton(player);
        if (preview == null) {
            return; // events page no longer open
        }
        ButtonData content = ButtonData.builder()
                .layers(eventPreviewDesign(item, lines, background))
                .id(EVENT_PREVIEW_ID)
                .build();
        preview.updateButton(content);
    }

    private static Button findEventPreviewButton(Player player) {
        ScreenPage page = PurrTechDisplayGUI.getPlugin().getDisplayManager().getPlayersMenu().get(player);
        if (page == null) {
            return null;
        }
        for (Button button : page.getButtons()) {
            if (EVENT_PREVIEW_ID.equals(button.getData().getId())) {
                return button;
            }
        }
        return null;
    }

    /** Leaving a row or the panel: blank the panel after the linger delay, unless the player is
     * on the panel itself or another row replaces it first (which cancels this). */
    private void scheduleEventPreviewHide(Player player) {
        PreviewState state = previewStates.get(player.getUniqueId());
        if (state != null && !state.hoveringPreview) {
            scheduleEventPreviewHide(player, state);
        }
    }

    private void scheduleEventPreviewHide(Player player, PreviewState state) {
        cancelEventPreviewHide(state);
        if (state.shownKey == null) {
            return;
        }
        state.hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            state.hideTask = null;
            state.shownKey = null;
            state.unitUuid = null;
            applyEventPreview(player, null, List.of(" "), TRANSPARENT);
        }, EVENT_PREVIEW_LINGER_TICKS);
    }

    private static void cancelEventPreviewHide(PreviewState state) {
        if (state.hideTask != null) {
            state.hideTask.cancel();
            state.hideTask = null;
        }
    }

    private void resetEventPreview(Player player) {
        PreviewState state = previewStates.remove(player.getUniqueId());
        if (state != null) {
            cancelEventPreviewHide(state);
        }
    }

    private void openPreviewedItem(Player player) {
        PreviewState state = previewStates.get(player.getUniqueId());
        if (state == null || state.shownKey == null) {
            return;
        }
        if (state.shulkerEvent) {
            long eventId = Long.parseLong(state.shownKey);
            resetEventPreview(player);
            openEventDetail(player, eventId);
            return;
        }
        if (state.unitUuid == null) {
            player.sendMessage("Tento event neobsahuje zadny item.");
            return;
        }
        resetEventPreview(player);
        openDetail(player, state.unitUuid);
    }

    // === Event detail page: who/what/where for a single event, opened by clicking its row on the
    // events list - per "aby se každá ta událost dala otevřít a viděl jsi podrobnosti". ===

    public void openEventDetail(Player player, long eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<EventRecord> found = eventDao.findById(eventId);
                if (found.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Udalost #" + eventId + " nenalezena."));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> openEventDetailPage(player, found.get()));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail udalosti selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("Lookup selhal, viz konzole."));
            }
        });
    }

    private void openEventDetailPage(Player player, EventRecord event) {
        // Overrides whatever openEventsPage registered - "když na to klikneš tak tě to tam portne,
        // ale pak když znovu otevřeš menu tak ti to otevře na samé stránce" is read here as "the
        // same page you teleported FROM", i.e. this detail page, not the list behind it.
        lastPage.put(player.getUniqueId(), () -> openEventDetail(player, event.id()));

        List<String> infoLines = new ArrayList<>();
        infoLines.add("Udalost #" + event.id() + " - " + CATEGORY_LABELS.getOrDefault(event.eventType(), event.eventType()));
        infoLines.add("Cas: " + formatTime(event.timestamp()));
        infoLines.add(event.playerUuid() != null ? "Player: " + resolvePlayerAlias(event.playerUuid()) : "Player: neznamy");
        if (event.gamemode() != null) {
            infoLines.add("Gamemode: " + event.gamemode());
        }
        infoLines.add(event.world() != null
                ? "world: " + event.world() + " " + event.x() + " " + event.y() + " " + event.z()
                : "world: neznamy");
        String detail = EventLineFormatter.formatDetail(event.detail());
        if (!detail.isEmpty()) {
            infoLines.add("Detail: " + detail);
        }

        // "taky třeba ukládej informace kdo byl v okolí třeba 50 blocků" - captured at write-time by
        // NearbyPlayers.capture, shown here as its own scrollable list.
        List<String> nearbyLines = event.nearbyPlayers() != null
                ? List.of(event.nearbyPlayers().split(","))
                : List.of();

        boolean hasLocation = event.world() != null && event.x() != null && event.y() != null && event.z() != null;

        List<ButtonData> buttons = new ArrayList<>();
        // Shulker session: inventory-style grid of its contents on the right, so the usual info +
        // nearby column moves left to make room.
        ShulkerSessionLog.Session shulker = ShulkerSessionLog.EVENT_TYPE.equals(event.eventType())
                ? ShulkerSessionLog.parse(event.detail()) : null;
        double infoX = 0;
        if (shulker != null) {
            infoX = -2.4;
            infoLines.add(1, "Shulker: " + shulker.shulkerMaterial() + " ("
                    + (ShulkerSessionLog.MODE_HELD.equals(shulker.mode()) ? "otevren v ruce" : "polozeny") + ")");
            addShulkerGridButtons(buttons, shulker);
        }
        buttons.add(infoTextButton(cx(infoX), cy(-1.3), 0.05, infoLines));
        buttons.add(scrollListButton("event-nearby", cx(infoX), cy(0.3), 0.05, nearbyLines,
                "(nikdo v okoli " + (int) NearbyPlayers.DEFAULT_RADIUS_BLOCKS + " bloku)"));
        if (hasLocation) {
            buttons.add(navButton("teleport", cx(-1.0), cy(1.4), 0.05, "Teleportovat", e -> teleportToEvent(player, event)));
            buttons.add(navButton("back", cx(1.0), cy(1.4), -0.05, "Zpet na seznam", e -> openEventsPage(player)));
        } else {
            buttons.add(navButton("back", cx(0), cy(1.4), -0.05, "Zpet na seznam", e -> openEventsPage(player)));
        }

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:event:" + event.id(), MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    // === Shulker session grid - "když to bude shulker tak ti to ukáže... co v něm bylo a co vzal
    // (označíš barvou, co přidal a odebral)... minecraft inventář layout". ===

    /** Visual size (blocks) of one slot square and the step between slots. */
    private static final double SHULKER_SLOT_BLOCKS = 0.42;
    private static final double SHULKER_SLOT_STEP = 0.46;
    /** Grid center, relative to screen center; rows start at the top row's y. */
    private static final double SHULKER_GRID_CENTER_X = 1.9;
    private static final double SHULKER_GRID_TOP_Y = -1.05;
    private static final Color SLOT_EMPTY = Color.fromARGB(170, 55, 55, 55);
    private static final Color SLOT_SAME = Color.fromARGB(200, 95, 95, 95);
    private static final Color SLOT_ADDED = Color.fromARGB(220, 40, 140, 40);
    private static final Color SLOT_REMOVED = Color.fromARGB(220, 165, 35, 35);
    private static final Color SLOT_REPLACED = Color.fromARGB(220, 175, 140, 20);

    /** 9x3 grid like the shulker's own inventory, plus a legend and a text list of the changes. */
    private void addShulkerGridButtons(List<ButtonData> buttons, ShulkerSessionLog.Session session) {
        List<ShulkerSessionLog.SlotDiff> diffs = ShulkerSessionLog.diff(session.before(), session.after());
        double left = SHULKER_GRID_CENTER_X - 4 * SHULKER_SLOT_STEP;

        List<String> changeLines = new ArrayList<>();
        for (ShulkerSessionLog.SlotDiff d : diffs) {
            int column = d.slot() % 9;
            int row = d.slot() / 9;
            // +y = down in this GUI; a slot's text background grows upward from its anchor, so the
            // anchor is the slot's bottom edge.
            double x = left + column * SHULKER_SLOT_STEP;
            double y = SHULKER_GRID_TOP_Y + row * SHULKER_SLOT_STEP + SHULKER_SLOT_BLOCKS;
            buttons.add(shulkerSlotButton("shulker-slot-" + d.slot(), cx(x), cy(y), 0.05, d));

            String line = switch (d.change()) {
                case ADDED -> "Slot " + d.slot() + ": pridal +" + d.delta() + " " + itemLabel(d.after());
                case REMOVED -> "Slot " + d.slot() + ": vzal " + (-d.delta()) + " " + itemLabel(d.before());
                case REPLACED -> "Slot " + d.slot() + ": " + d.before().getAmount() + " " + itemLabel(d.before())
                        + " -> " + d.after().getAmount() + " " + itemLabel(d.after());
                case SAME -> null;
            };
            if (line != null) {
                changeLines.add(line);
            }
        }

        int added = ShulkerSessionLog.totalAdded(diffs);
        int removed = ShulkerSessionLog.totalRemoved(diffs);
        double belowGrid = SHULKER_GRID_TOP_Y + 3 * SHULKER_SLOT_STEP + 0.3;
        buttons.add(buildStaticText("shulker-legend", cx(SHULKER_GRID_CENTER_X), cy(belowGrid), 0.05, List.of(
                "Pridal: " + added + " ks  |  Vzal: " + removed + " ks",
                "zelena = pridal, cervena = vzal, zluta = vymenil")));
        buttons.add(scrollListButton("shulker-changes", cx(SHULKER_GRID_CENTER_X), cy(belowGrid + 1.1), 0.05,
                changeLines, "(obsah se nezmenil)"));
    }

    /**
     * One inventory slot: a colored square (text background scaled to a fixed size), the item on
     * it, and its amount / change in the corner. A taken-out item is still drawn (on red) so it's
     * visible what was taken. Hovering shows the item's name in the action bar.
     */
    private ButtonData shulkerSlotButton(String id, double x, double y, double z, ShulkerSessionLog.SlotDiff d) {
        ItemStack shown = d.change() == ShulkerSessionLog.Change.REMOVED ? d.before() : d.after();
        if (shown == null) {
            shown = d.before();
        }
        Color background = switch (d.change()) {
            case ADDED -> SLOT_ADDED;
            case REMOVED -> SLOT_REMOVED;
            case REPLACED -> SLOT_REPLACED;
            case SAME -> shown != null ? SLOT_SAME : SLOT_EMPTY;
        };

        TextDisplayLayerData square = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-bg", 1)
                .setText(List.of(" "))
                .setBackground(background);
        square.setAutoFitText(false);
        double naturalWidth = Math.max(0.05, square.estimateContentWidthBlocks());
        double naturalHeight = Math.max(0.05, square.estimateContentHeightBlocks());
        square.setScale(new Vector3f((float) (SHULKER_SLOT_BLOCKS / naturalWidth),
                (float) (SHULKER_SLOT_BLOCKS / naturalHeight), 1f));

        List<DisplayLayerData> layers = new ArrayList<>();
        layers.add(square);
        String hoverText = "Prazdny slot";
        if (shown != null) {
            ItemDisplayLayerData item = new ItemDisplayLayerData(0, -SHULKER_SLOT_BLOCKS / 2.0, 0.02, GUI_PATH, id + "-item", 2)
                    .setItemStack(shown);
            float itemScale = (float) (SHULKER_SLOT_BLOCKS * 0.8);
            item.setScale(new Vector3f(itemScale, itemScale, itemScale));
            layers.add(item);

            String count = switch (d.change()) {
                case ADDED -> "+" + d.delta();
                case REMOVED -> String.valueOf(d.delta());
                default -> shown.getAmount() > 1 ? String.valueOf(shown.getAmount()) : "";
            };
            if (!count.isEmpty()) {
                TextDisplayLayerData countText = new TextDisplayLayerData(
                        SHULKER_SLOT_BLOCKS * 0.22, 0, 0.03, GUI_PATH, id + "-count", 3)
                        .setText(List.of(count))
                        .setBackground(TRANSPARENT);
                countText.setAutoFitText(false);
                countText.setScale(new Vector3f(0.5f, 0.5f, 1f));
                layers.add(countText);
            }
            hoverText = itemLabel(shown) + " x" + shown.getAmount() + switch (d.change()) {
                case ADDED -> " (pridal " + d.delta() + ")";
                case REMOVED -> " (vzal " + (-d.delta()) + ")";
                case REPLACED -> " (driv " + d.before().getAmount() + " " + itemLabel(d.before()) + ")";
                case SAME -> "";
            };
        }

        double sizePixels = SHULKER_SLOT_BLOCKS * PIXELS_PER_BLOCK;
        String finalHoverText = hoverText;
        return ButtonData.builder()
                .at(x, y, z)
                .size(sizePixels, sizePixels)
                .layers(new LayersData(layers, GUI_PATH + ":" + id, GUI_PATH))
                .id(id)
                .onHoverStart(ctx -> ctx.player().sendActionBar(net.kyori.adventure.text.Component.text(finalHoverText)))
                .hitboxOffsetZ(hitboxRecessZ(sizePixels))
                .build();
    }

    /** Display name if the item has one, else its material. */
    private static String itemLabel(ItemStack item) {
        if (item == null) {
            return "?";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return item.getType().name();
    }

    /** Non-clickable multi-line text panel with its own id (unlike {@link #infoTextButton}, whose
     * id is fixed, so there can be several per page). */
    private ButtonData buildStaticText(String id, double x, double y, double z, List<String> lines) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1)
                .setText(lines)
                .setBackground(PANEL_BACKGROUND);
        return ButtonData.builder()
                .at(x, y, z)
                .size(30, 10)
                .layers(new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH))
                .id(id)
                .hitboxOffsetZ(hitboxRecessZ(30))
                .build();
    }

    /** "když na to klikneš tak tě to tam portne" - teleports to the event's recorded block
     * coordinates (center of the block, at its Y - these are block coords, not exact sub-block
     * positions). No-ops with a chat message instead of throwing if the event has no location or
     * its world isn't currently loaded. */
    private void teleportToEvent(Player player, EventRecord event) {
        if (event.world() == null || event.x() == null || event.y() == null || event.z() == null) {
            player.sendMessage("Tato udalost nema ulozenou polohu.");
            return;
        }
        World world = Bukkit.getWorld(event.world());
        if (world == null) {
            player.sendMessage("Svet '" + event.world() + "' neni nacteny.");
            return;
        }
        DisplayGuiAPI.closeMenu(player);
        player.teleport(new Location(world, event.x() + 0.5, event.y(), event.z() + 0.5));
    }

    // === Small in-game calendar (replaces the old chat-based "Napis do chatu datum" flow) - per
    // "udělat tam nějaký malý kalendář... budou moct přepínat mezi měsíci". ===

    private void openEventsCalendar(Player player, boolean from) {
        CalendarState existing = calendarSessions.get(player.getUniqueId());
        YearMonth month = existing != null ? existing.month : YearMonth.now();
        calendarSessions.put(player.getUniqueId(), new CalendarState(from, month));
        openCalendarPage(player);
    }

    private void shiftCalendarMonth(Player player, int delta) {
        CalendarState state = calendarSessions.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.month = state.month.plusMonths(delta);
        openCalendarPage(player);
    }

    private void pickCalendarDay(Player player, int day) {
        CalendarState state = calendarSessions.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        LocalDate date = state.month.atDay(day);
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        Long otherBoundMillis = state.from ? filter.to : filter.from;
        // The calendar page only wires this handler to already-valid day buttons (see
        // openCalendarPage), but re-check anyway - defends against a stale render (e.g. midnight
        // passed, or the other bound changed, while this page was still open).
        if (!isValidEventsDate(date, state.from, otherBoundMillis)) {
            player.sendMessage(invalidEventsDateReason(date, otherBoundMillis));
            return;
        }
        long millis = (state.from ? date.atStartOfDay() : date.atTime(23, 59, 59))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (state.from) {
            filter.from = millis;
        } else {
            filter.to = millis;
        }
        calendarSessions.remove(player.getUniqueId());
        openEventsPage(player);
    }

    private void cancelCalendar(Player player) {
        calendarSessions.remove(player.getUniqueId());
        openEventsPage(player);
    }

    /**
     * A day is pickable for the "from"/"to" events-date filter iff (a) it isn't in the future - no
     * log can exist for a day that hasn't happened yet - and (b) it doesn't cross the OTHER bound
     * already set on this filter (Od must stay &lt;= Do). Shared by the calendar's day-grid
     * rendering, {@link #pickCalendarDay} and {@link #applyManualDateInput} so all three entry
     * points enforce the exact same rule - per "vyznač ty datumy který jdou použít... nedávat do
     * budoucna".
     */
    private static boolean isValidEventsDate(LocalDate date, boolean from, Long otherBoundMillis) {
        if (date.isAfter(LocalDate.now())) {
            return false;
        }
        if (otherBoundMillis == null) {
            return true;
        }
        LocalDate otherDate = Instant.ofEpochMilli(otherBoundMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        return from ? !date.isAfter(otherDate) : !date.isBefore(otherDate);
    }

    private static String invalidEventsDateReason(LocalDate date, Long otherBoundMillis) {
        if (date.isAfter(LocalDate.now())) {
            return "Tento den jeste neprobehl.";
        }
        return "Datum by bylo mimo platny rozsah (Od musi byt <= Do).";
    }

    /** Manual chat-entry fallback for a date the calendar can't easily reach (far past months) -
     * per "dej tam individuální tlačítko na zadání hodnoty do chatu". Keeps the calendar's
     * from/to session alive so {@link #applyManualDateInput} knows which bound to set. */
    private void beginManualDateInput(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.EVENTS_DATE);
        player.sendMessage("Napis do chatu datum (napr. 2026-09-19 nebo 2026-09-19 14:30:00) - "
                + "nesmi byt v budoucnu a musi sedet s druhym ohranicenim (Od <= Do), "
                + "nebo 'zrusit' pro zruseni.");
    }

    private void applyManualDateInput(Player player, String raw) {
        CalendarState state = calendarSessions.get(player.getUniqueId());
        if (state == null) {
            player.sendMessage("Zadavani vyprselo, otevri kalendar znovu z menu udalosti.");
            return;
        }
        String trimmed = raw.trim();
        LocalDateTime dateTime = tryParse(trimmed, DATE_TIME_SEC);
        if (dateTime == null) {
            dateTime = tryParse(trimmed, DATE_TIME_MIN);
        }
        LocalDate date;
        if (dateTime != null) {
            date = dateTime.toLocalDate();
        } else {
            try {
                date = LocalDate.parse(trimmed, DATE_ONLY);
                dateTime = state.from ? date.atStartOfDay() : date.atTime(23, 59, 59);
            } catch (DateTimeParseException ignored) {
                player.sendMessage("Neplatny format data: '" + raw + "' (pouzij napr. 2026-09-19 nebo 2026-09-19 14:30:00).");
                return;
            }
        }
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        Long otherBoundMillis = state.from ? filter.to : filter.from;
        if (!isValidEventsDate(date, state.from, otherBoundMillis)) {
            player.sendMessage(invalidEventsDateReason(date, otherBoundMillis));
            return;
        }
        long millis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (state.from) {
            filter.from = millis;
        } else {
            filter.to = millis;
        }
        calendarSessions.remove(player.getUniqueId());
        openEventsPage(player);
    }

    private static LocalDateTime tryParse(String raw, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(raw, formatter);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Really small buttons in a 7-column Monday-first grid ("fakt malý tlačítka, jedno vedle
     * druhého"): a month-nav row, a PO/UT/ST/CT/PA/SO/NE weekday header row, then one button per
     * day of the month positioned by {@link LocalDate#getDayOfWeek()} (Monday=1). Uses the same
     * compact hitbox-per-label sizing as everything else (see {@link #buildTextButton}) - day
     * numbers are 1-2 chars so the buttons end up genuinely tiny on their own.
     */
    private void openCalendarPage(Player player) {
        CalendarState state = calendarSessions.get(player.getUniqueId());
        if (state == null) {
            openEventsPage(player);
            return;
        }
        List<ButtonData> buttons = new ArrayList<>();
        // 0.85 (not tighter) because most day cells are 2-digit ("10".."31") - at HITBOX_PADDING_PX
        // that's close to 0.7 blocks wide already, so a tighter step would overlap right back.
        double colStep = 0.85;
        double centerX = 0;
        double startX = centerX - 3 * colStep;

        buttons.add(navButton("cal-prev", cx(startX), cy(-1.3), 0.05, "<", e -> shiftCalendarMonth(player, -1)));
        // Title/weekday headers use navButton (no-op click) rather than infoTextButton - that helper
        // has a large fixed 30x10px (1.875 blocks wide) footprint meant for standalone info panels,
        // which would badly overlap its neighbors at this grid's 0.85-block column step.
        buttons.add(navButton("cal-title", cx(centerX), cy(-1.3), 0.05,
                MONTH_NAMES.get(state.month.getMonthValue() - 1) + " " + state.month.getYear(), e -> {}));
        buttons.add(navButton("cal-next", cx(startX + 6 * colStep), cy(-1.3), 0.05, ">", e -> shiftCalendarMonth(player, 1)));

        double headerY = -0.95;
        for (int i = 0; i < WEEKDAY_HEADERS.size(); i++) {
            buttons.add(navButton("cal-hdr-" + i, cx(startX + i * colStep), cy(headerY), 0.05,
                    WEEKDAY_HEADERS.get(i), e -> {}));
        }

        // Days that can't actually be picked (future, or would cross the other already-set Od/Do
        // bound) render in BUTTON_BACKGROUND_DISABLED and clicking them explains why instead of
        // silently doing nothing or applying an invalid range - per "vyznač ty datumy který jdou
        // použít nebo naopak který nejdou použít".
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        Long otherBoundMillis = state.from ? filter.to : filter.from;
        double gridStartY = -0.6;
        double rowStep = 0.35;
        int daysInMonth = state.month.lengthOfMonth();
        int firstDayColumn = state.month.atDay(1).getDayOfWeek().getValue() - 1; // Monday=0
        for (int day = 1; day <= daysInMonth; day++) {
            int cell = firstDayColumn + day - 1;
            int col = cell % 7;
            int row = cell / 7;
            double x = startX + col * colStep;
            double y = gridStartY + row * rowStep;
            int pickedDay = day;
            LocalDate cellDate = state.month.atDay(day);
            boolean valid = isValidEventsDate(cellDate, state.from, otherBoundMillis);
            Consumer<MenuButtonClickEvent> onClick = valid
                    ? e -> pickCalendarDay(player, pickedDay)
                    : e -> player.sendMessage(invalidEventsDateReason(cellDate, otherBoundMillis));
            buttons.add(calendarDayButton("cal-day-" + day, cx(x), cy(y), 0.05, day, valid, onClick));
        }

        int lastRow = (firstDayColumn + daysInMonth - 1) / 7;
        double afterGridY = gridStartY + lastRow * rowStep + 0.3;
        buttons.add(navButton("cal-cancel", cx(centerX - 0.9), cy(afterGridY), 0.05, "Zrusit", e -> cancelCalendar(player)));
        buttons.add(navButton("cal-manual", cx(centerX + 0.9), cy(afterGridY), 0.05, "Napsat rucne", e -> beginManualDateInput(player)));

        String pageKey = "purrtechlog:calendar:" + (state.from ? "from" : "to") + ":" + state.month;
        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, pageKey, MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    // === On-screen keyboard: menu-only free-text entry (UUID search for now; player search and
    // date-range entry are migrated off chat in later phases). One click = one character; state
    // lives in keyboardSessions, page is fully rebuilt on every click same as the rest of this file. ===

    private void openUuidSearchKeyboard(Player player) {
        keyboardSessions.put(player.getUniqueId(), new KeyboardSession(
                "Hledat podle UUID", HEX_KEYS, 8, 32, true,
                raw -> openDetail(player, insertUuidDashes(raw)),
                () -> openMainMenu(player)));
        openKeyboardPage(player);
    }

    private void openKeyboardPage(Player player) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            openMainMenu(player);
            return;
        }
        List<ButtonData> buttons = new ArrayList<>();

        String typed = session.buffer.toString();
        String preview = session.uuidFormat ? previewUuidDashes(typed) : typed;
        List<String> infoLines = new ArrayList<>();
        infoLines.add(session.title);
        infoLines.add(preview.isEmpty() ? "-" : preview);
        infoLines.add(typed.length() + "/" + session.maxLength + " znaku");
        buttons.add(infoTextButton(cx(0), cy(-2.0), 0.04, infoLines));

        // Key grid: step 0.75x0.5 blocks, hitbox 11x7px = 0.6875x0.4375 blocks - comfortably under
        // the step on both axes (see [[reference-purrtechdisplaygui-coordinate-rules]]).
        int columns = session.columns;
        double stepX = 0.75;
        double startX = -(columns - 1) * stepX / 2.0;   // centers the whole grid on rel x=0
        double startY = -1.2;
        double stepY = 0.5;
        for (int i = 0; i < session.keys.size(); i++) {
            String key = session.keys.get(i);
            double x = startX + (i % columns) * stepX;
            double y = startY + (i / columns) * stepY;
            buttons.add(navButton("key-" + i, cx(x), cy(y), 0.05, 11, 7, key.toUpperCase(),
                    e -> appendKeyboardChar(player, key)));
        }
        int rows = (session.keys.size() + columns - 1) / columns;
        double controlY = startY + (rows - 1) * stepY + 0.5;

        // Control row: 2x2 grid, step 1.6x0.5 blocks, hitbox 24x7px = 1.5x0.4375 blocks - again
        // comfortably under the step (see [[reference-purrtechdisplaygui-coordinate-rules]]).
        boolean canConfirm = typed.length() == session.maxLength;
        buttons.add(navButton("kb-backspace", cx(-0.8), cy(controlY), 0.05, 24, 7, "Smazat", e -> backspaceKeyboard(player)));
        buttons.add(navButton("kb-clear", cx(0.8), cy(controlY), 0.05, 24, 7, "Vymazat", e -> clearKeyboard(player)));
        buttons.add(navButton("kb-cancel", cx(-0.8), cy(controlY + 0.5), 0.05, 24, 7, "Zrusit", e -> cancelKeyboard(player)));
        buttons.add(navButton("kb-confirm", cx(0.8), cy(controlY + 0.5), 0.05, 24, 7, "Potvrdit",
                e -> confirmKeyboard(player), canConfirm));
        // Manual chat-entry fallback, per "u zadávání UUID dej taky klidně tlačítko na zadání do
        // chatu" - same idea as the calendar's "Napsat rucne" button, so a full UUID doesn't need
        // 32 individual hex-key clicks.
        buttons.add(navButton("kb-manual", cx(0), cy(controlY + 1.0), 0.05, 60, 7, "Napsat do chatu",
                e -> beginManualKeyboardInput(player)));

        ScreenPageData screen = new ScreenPageData(backgroundPage(), buttons, "purrtechlog:keyboard:" + typed.length(), MENU_DISTANCE_PIXELS);
        DisplayGuiAPI.openMenu(player, screen);
    }

    private void appendKeyboardChar(Player player, String key) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.buffer.length() < session.maxLength) {
            session.buffer.append(key);
        }
        openKeyboardPage(player);
    }

    private void backspaceKeyboard(Player player) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session != null && !session.buffer.isEmpty()) {
            session.buffer.setLength(session.buffer.length() - 1);
        }
        openKeyboardPage(player);
    }

    private void clearKeyboard(Player player) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session != null) {
            session.buffer.setLength(0);
        }
        openKeyboardPage(player);
    }

    private void cancelKeyboard(Player player) {
        KeyboardSession session = keyboardSessions.remove(player.getUniqueId());
        if (session != null) {
            session.onCancel.run();
        } else {
            openMainMenu(player);
        }
    }

    private void confirmKeyboard(Player player) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.buffer.length() != session.maxLength) {
            player.sendMessage("Nejdriv zadej vsech " + session.maxLength + " znaku.");
            return;
        }
        keyboardSessions.remove(player.getUniqueId());
        session.onConfirm.accept(session.buffer.toString());
    }

    /** Manual chat-entry fallback for whatever on-screen keyboard is currently open (today: UUID
     * search) - keeps the session alive so {@link #applyManualKeyboardInput} still knows the
     * target/columns/maxLength/uuidFormat and can reuse {@link #confirmKeyboard}'s own validation
     * instead of duplicating it, per [[reference-purrtechdisplaygui-coordinate-rules]]'s "validate
     * at every entry point via one shared method" rule. */
    private void beginManualKeyboardInput(Player player) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            openMainMenu(player);
            return;
        }
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.KEYBOARD_MANUAL);
        String what = session.uuidFormat ? "UUID (32 hex znaku, pomlcky nevadi)"
                : "hodnotu (" + session.maxLength + " znaku, povolene: " + String.join("", session.keys) + ")";
        player.sendMessage("Napis do chatu " + what + " nebo 'zrusit'.");
    }

    private void applyManualKeyboardInput(Player player, String raw) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("Zadavani vyprselo, otevri klavesnici znovu z menu.");
            return;
        }
        String normalized = session.uuidFormat ? raw.trim().replace("-", "").toLowerCase(Locale.ROOT) : raw.trim();
        if (normalized.length() != session.maxLength || !isFromAllowedKeys(normalized, session.keys)) {
            player.sendMessage("Neplatna hodnota: '" + raw + "' - musi mit presne " + session.maxLength
                    + " znaku z povolene sady (" + String.join("", session.keys) + ").");
            return;
        }
        session.buffer.setLength(0);
        session.buffer.append(normalized);
        confirmKeyboard(player);
    }

    /** Every character of {@code value} must be one of {@code allowedKeys} (case-insensitive) -
     * generic equivalent of what clicking only the on-screen key buttons already guarantees, so
     * chat-typed input can't smuggle in a character the keyboard itself would never produce. */
    private static boolean isFromAllowedKeys(String value, List<String> allowedKeys) {
        for (int i = 0; i < value.length(); i++) {
            String c = String.valueOf(value.charAt(i));
            if (!allowedKeys.contains(c)) {
                return false;
            }
        }
        return true;
    }

    /** Live preview while typing - inserts a dash at each UUID group boundary (8-4-4-4-12) as soon
     * as the buffer reaches it, without requiring the full 32 characters first. */
    private static String previewUuidDashes(String hex) {
        StringBuilder sb = new StringBuilder();
        int[] boundaries = {8, 12, 16, 20};
        for (int i = 0; i < hex.length(); i++) {
            for (int b : boundaries) {
                if (i == b) {
                    sb.append('-');
                }
            }
            sb.append(hex.charAt(i));
        }
        return sb.toString();
    }

    private static String insertUuidDashes(String hex32) {
        return hex32.substring(0, 8) + "-" + hex32.substring(8, 12) + "-" + hex32.substring(12, 16)
                + "-" + hex32.substring(16, 20) + "-" + hex32.substring(20, 32);
    }
}
