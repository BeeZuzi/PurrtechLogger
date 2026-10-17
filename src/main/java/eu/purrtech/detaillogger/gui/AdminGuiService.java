package eu.purrtech.detaillogger.gui;

import eu.purrtech.detaillogger.db.dao.DupeAlertDao;
import eu.purrtech.detaillogger.db.dao.DupeAlertRecord;
import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.detaillogger.db.dao.EventRecord;
import eu.purrtech.detaillogger.lang.Lang;
import eu.purrtech.detaillogger.db.dao.LineageDao;
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
import org.bukkit.entity.TextDisplay;
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
    /** The filter column on the LEFT: the same angle as the events list on the right, the opposite way,
     * so the two halves face each other symmetrically ("stejnou velikostí úhlu... opačným směrem"). */
    private static final float EVENTS_FILTER_ROTATION_Y_DEGREES = -EVENTS_LIST_ROTATION_Y_DEGREES;
    /** Air (blocks, along X) between the filter panel's right edge and the main panel's left edge. */
    private static final double EVENTS_FILTER_GAP_BLOCKS = 0.05;
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
            ShulkerSessionLog.EVENT_TYPE, "VANISHED_BY_COMMAND");

    /** Short label of an event type ("category.<TYPE>" in lang/*.yml); the raw type when not listed. */
    private static String categoryLabel(String type) {
        String label = Lang.t("category." + type);
        return label.equals("category." + type) ? type : label;
    }

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

    private enum SearchMode { PLAYER, EVENTS_DATE, KEYBOARD_MANUAL, PICKER }

    /** Per-player state for the events page - active category (null = all), optional time bounds,
     * and whether timestamps render as absolute dates or "pred X". No more pagination offset - the
     * events list is a single {@code button_list_scroll} now, scrolled by mouse wheel instead of
     * paged with prev/next buttons. */
    private static final class EventsFilter {
        /** Checked categories. Empty = no category filter = "Vse" (so "Vse" can never be on together
         * with any other box - it simply means none of them are checked). */
        private final java.util.Set<String> activeCategories = new java.util.LinkedHashSet<>();
        /** Checked players (UUID strings). Empty = every player. Same idea as the categories. */
        private final java.util.Set<String> activePlayers = new java.util.LinkedHashSet<>();
        /** Scroll position of the player picker, kept across the reloads a click causes. */
        private int pickerOffset;
        /** Player-picker name search typed in chat ("Hledat hrace"); null = list everyone. */
        private String pickerQuery;
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
    private final LineageDao lineageDao;
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
                            LineageDao lineageDao, ItemTrackingService itemTracking, Plugin plugin, Logger logger) {
        this.locationDao = locationDao;
        this.lineageDao = lineageDao;
        this.itemTracking = itemTracking;
        this.historyService = historyService;
        this.templateDao = templateDao;
        this.dupeAlertDao = dupeAlertDao;
        this.playerDirectory = playerDirectory;
        this.eventDao = eventDao;
        this.plugin = plugin;
        Skin.configure(plugin.getConfig(), "gui.textures", true);
        this.logger = logger;
    }

    public void openMainMenu(Player player) {
        lastPage.remove(player.getUniqueId());
        // Single centered column, tight 0.35-block step, rel y -0.4..1.35 - straddles eye level
        // (0) and stays well short of the ~1.5-below-eye "feet" line (see the height-anchor note
        // in [[reference-purrtechdisplaygui-coordinate-rules]]) instead of sinking into the floor
        // like the first (too-deep, rel y up to 2.55) attempt did.
        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(navButton("players", cx(0), cy(-0.40), 0.04, Lang.t("gui.main.players"), e -> openPlayerList(player, 0)));
        buttons.add(navButton("search", cx(0), cy(-0.05), 0.04, Lang.t("gui.main.search-uuid"), e -> openUuidSearchKeyboard(player)));
        buttons.add(navButton("alerts", cx(0), cy(0.30), 0.05, Lang.t("gui.main.alerts"), e -> openAlerts(player)));
        buttons.add(navButton("close", cx(0), cy(0.65), 0.05, Lang.t("gui.main.close"), e -> DisplayGuiAPI.closeMenu(player)));
        buttons.add(navButton("events", cx(0), cy(1.00), 0.05, Lang.t("gui.main.events"), e -> openEventsPage(player)));

        openScreen(player, buttons, "purrtechlog:main");
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
        player.sendMessage(Lang.t("chat.prompt-player", Lang.t("chat.cancel-word")));
    }

    /** Player picker's "Hledat hrace": the next chat line filters its list by name (menu stays open). */
    private void beginPickerSearch(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.PICKER);
        player.sendMessage(Lang.t("chat.prompt-picker", Lang.t("chat.cancel-word")));
    }

    private void applyPickerSearch(Player player, String raw) {
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        filter.pickerQuery = raw.isBlank() ? null : raw;
        filter.pickerOffset = 0;
        openPlayerPicker(player);
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
            if (raw.equalsIgnoreCase(Lang.t("chat.cancel-word")) || raw.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.t(mode == SearchMode.EVENTS_DATE ? "chat.input-cancelled" : "chat.search-cancelled"));
                return;
            }
            switch (mode) {
                case PLAYER -> openPlayerSearch(player, raw);
                case EVENTS_DATE -> applyManualDateInput(player, raw);
                case KEYBOARD_MANUAL -> applyManualKeyboardInput(player, raw);
                case PICKER -> applyPickerSearch(player, raw);
            }
        });
    }

    public void openDetail(Player player, String rawUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid.trim());
        } catch (IllegalArgumentException e) {
            player.sendMessage(Lang.t("chat.invalid-uuid", rawUuid));
            return;
        }
        String uuidString = uuid.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<HistoryService.UnitHistory> found = historyService.lookup(uuidString);
                if (found.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.unknown-item", uuidString)));
                    return;
                }
                String material = templateDao.findMaterialById(found.get().unit().templateId());
                UnitLocationRecord location = locationDao.findByUnit(uuidString);
                List<LineageDao.Link> parents = lineageDao.findParents(uuidString);
                List<LineageDao.Link> children = lineageDao.findChildren(uuidString);
                Bukkit.getScheduler().runTask(plugin,
                        () -> openDetailPage(player, found.get(), material, location, parents, children));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    /** Item detail page layout - history list left, item + its info right ("UUID a další
     * informace ohledně daného itemu dej doprava"). Not yet confirmed in-game. */
    // Was -1.3 / bottom row at -1.0+4*0.65: the bottom row sat on the "Zpet" button (cx(0), cy(1.6)).
    // Moved left to -2.1 and the whole list up 0.5 (see ITEM_HISTORY_TOP_Y).
    // -2.6: the history panel got wider (sized by its rows now), -2.3 overlapped the item panel by ~0.15
    private static final double ITEM_HISTORY_COLUMN_X = -2.6;
    private static final double ITEM_HISTORY_TOP_Y = -1.5;
    /** 0 = the history faces the player flat, like the picker ("dáš 0 stupňů"). */
    private static final float ITEM_HISTORY_ROTATION_Y_DEGREES = 0f;
    private static final double ITEM_INFO_COLUMN_X = 2.4;
    /** Bottom of the item info card (it grows up from here) and of the back button below it. */
    private static final double ITEM_INFO_BOTTOM_Y = 1.0, ITEM_BACK_Y = 1.45;
    /** Extra width (blocks) of the history panel on each side ("rozšiř do stran"). */
    private static final double ITEM_HISTORY_PANEL_PAD_X = 0.25;
    /** Gap (blocks) between the info card's right edge and its line buttons (copy / teleport). */
    private static final double ITEM_LINE_BUTTON_GAP = 0.15;

    /**
     * A compact button right of the info card, on the (first) displayed line starting with
     * {@code linePrefix} (colour codes ignored). The card is left-aligned text growing up from its
     * bottom, so line k's center is {@code top + (k + 0.5) * lineHeight}.
     */
    private void addInfoLineButton(List<ButtonData> buttons, ButtonData card, List<String> lines, String linePrefix,
                                   String id, String label, Consumer<MenuButtonClickEvent> onClick) {
        TextDisplayLayerData text = (TextDisplayLayerData) card.getLayersData().getDisplayLayerData().values().iterator().next();
        List<String> shown = text.getDisplayTextLines();
        int index = -1;
        for (int k = 0; k < shown.size(); k++) {
            if (shown.get(k).replaceAll("§.", "").startsWith(linePrefix)) {
                index = k;
                break;
            }
        }
        if (index < 0) return;
        double lineHeight = text.estimateContentHeightBlocks() / shown.size();
        double cardTop = card.getY() - text.estimateContentHeightBlocks();
        double lineCenter = cardTop + (index + 0.5) * lineHeight;
        ButtonData button = compactButton(id, 0, 0, 0.05, label, onClick, false);
        // a button grows up from its anchor too -> anchor half its height below the line center
        double x = card.getX() + card.getWidth() / 2 + ITEM_LINE_BUTTON_GAP + button.getWidth() / 2;
        buttons.add(compactButton(id, x, lineCenter + button.getHeight() / 2, 0.05, label, onClick, false));
    }

    /** Chat line with a click-to-copy value ("pošle se mu to do chatu kde na to klikne a zkopíruje se"). */
    private static void sendCopyable(Player player, String label, String value) {
        player.sendMessage(net.kyori.adventure.text.Component.text(label, net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text(value, net.kyori.adventure.text.format.NamedTextColor.WHITE)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(value))
                        .hoverEvent(net.kyori.adventure.text.Component.text(Lang.t("chat.click-to-copy")))));
    }

    /** A recorded block position, or a holder (inventory / ender chest) who is online. */
    private static boolean canTeleport(UnitLocationRecord loc) {
        if (loc == null) return false;
        if (loc.world() != null && loc.x() != null && loc.y() != null && loc.z() != null) return true;
        return loc.playerUuid() != null && Bukkit.getPlayer(UUID.fromString(loc.playerUuid())) != null;
    }

    private void teleportToUnit(Player player, UnitLocationRecord loc) {
        Location target = null;
        if (loc.world() != null && loc.x() != null && loc.y() != null && loc.z() != null) {
            World world = Bukkit.getWorld(loc.world());
            if (world == null) {
                player.sendMessage(Lang.t("chat.world-not-loaded", loc.world()));
                return;
            }
            target = new Location(world, loc.x() + 0.5, loc.y(), loc.z() + 0.5);
        } else if (loc.playerUuid() != null) {
            Player holder = Bukkit.getPlayer(UUID.fromString(loc.playerUuid()));
            if (holder != null) target = holder.getLocation();
        }
        if (target == null) {
            player.sendMessage(Lang.t("chat.item-location-unknown"));
            return;
        }
        DisplayGuiAPI.closeMenu(player);
        player.teleport(target);
    }
    private static final String ITEM_ICON_ID = "item-icon";
    private static final double ITEM_ICON_BLOCKS = 0.8;

    /** Up to 3 lines of "what this unit was made from / turned into": the other unit's short UUID, or
     * what an untracked result became (e.g. DIAMOND_BLOCK), plus how and when. */
    private static void addLineageLines(List<String> lines, String label, List<LineageDao.Link> links) {
        for (int i = 0; i < links.size() && i < 3; i++) {
            LineageDao.Link link = links.get(i);
            String other = link.otherUuid() != null ? link.otherUuid().substring(0, 8) + "..."
                    : link.detail() != null ? link.detail() : "?";
            lines.add(label + ": " + other + " (" + link.relation().replace("TRANSFORMED_", "").toLowerCase(Locale.ROOT)
                    + ", " + formatTime(link.at()) + ")");
        }
        if (links.size() > 3) {
            lines.add(label + ": " + Lang.t("gui.item.lineage-more", links.size() - 3));
        }
    }

    private void openDetailPage(Player player, HistoryService.UnitHistory history, String materialName,
                                UnitLocationRecord location, List<LineageDao.Link> parents,
                                List<LineageDao.Link> children) {
        TrackedUnitRecord unit = history.unit();
        // The real item (enchant glint, custom model, name) if its last location is loaded -
        // otherwise just its template material ("místo stonu bude ten item").
        ItemStack live = findLiveItem(unit.uuid(), location);
        ItemStack icon = live != null ? live : iconFor(materialName);

        // Sections like the hover preview: title, then item / state / place / lineage, label grey,
        // value white, state green/red ("k tomu popisu ... design aby to bylo přehlednější").
        List<String> infoLines = new ArrayList<>();
        infoLines.add("§6" + itemTitle(live, materialName));
        infoLines.add(MUTED + unit.uuid());
        infoLines.add("");
        infoLines.add(LABEL + Lang.t("gui.item.material") + VALUE + (materialName != null ? materialName : "?"));
        if (live != null) {
            infoLines.add(LABEL + Lang.t("gui.item.amount") + VALUE + Lang.t("gui.pieces", live.getAmount()));
            infoLines.add(LABEL + Lang.t("gui.item.enchants") + VALUE + formatEnchants(live));
        } else {
            infoLines.add(MUTED + Lang.t("gui.item.not-loaded"));
        }
        infoLines.add(LABEL + Lang.t("gui.item.kind") + VALUE + unit.kind() + LABEL + "  " + Lang.t("gui.item.origin") + VALUE + unit.origin());
        infoLines.add("");
        infoLines.add(LABEL + Lang.t("gui.item.state") + (unit.alive() ? GOOD + Lang.t("gui.item.alive")
                : BAD + Lang.t("gui.item.destroyed") + " " + MUTED + "(" + unit.destroyedCause() + ", " + formatTime(unit.destroyedAt()) + ")"));
        infoLines.add(LABEL + Lang.t("gui.item.genesis") + VALUE + formatTime(unit.genesisAt()));
        if (unit.duplicatedFromUuid() != null) {
            infoLines.add(BAD + Lang.t("gui.item.duplicate-of") + VALUE + shortUuid(unit.duplicatedFromUuid()));
        }
        infoLines.add(LABEL + Lang.t("gui.item.location") + VALUE + formatUnitLocation(location));
        if (!parents.isEmpty() || !children.isEmpty()) {
            infoLines.add("");
            addLineageLines(infoLines, LABEL + Lang.t("gui.item.made-from") + VALUE, parents);
            addLineageLines(infoLines, LABEL + Lang.t("gui.item.turned-into") + VALUE, children);
        }

        // History as the same clickable scroll list as the events menu, newest first like there.
        List<EventRecord> events = new ArrayList<>(history.events());
        events.sort(Comparator.comparingLong(EventRecord::timestamp).reversed());
        double rowStepY = 0.65;
        double bottomRowY = ITEM_HISTORY_TOP_Y + (EVENTS_VISIBLE_ROWS - 1) * rowStepY;
        EventsFilter filter = eventsFilters.get(player.getUniqueId());
        boolean relativeTime = filter != null && filter.relativeTime;
        Map<String, String> materials = materialName != null ? Map.of(unit.uuid(), materialName) : Map.of();

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(eventsListButton(player, "item-history", cx(ITEM_HISTORY_COLUMN_X), cy(bottomRowY), FLAT_LIST_Z,
                events, materials, relativeTime, rowStepY, ITEM_HISTORY_ROTATION_Y_DEGREES, false));
        ButtonData info = infoCard("item-info", cx(ITEM_INFO_COLUMN_X), cy(ITEM_INFO_BOTTOM_Y), 0.05, infoLines);
        // the icon sits just above the card, however tall the card turned out
        double infoTop = ITEM_INFO_BOTTOM_Y - info.getHeight(); // getHeight() is already in blocks
        buttons.add(itemIconButton(player, cx(ITEM_INFO_COLUMN_X), cy(infoTop - 0.15), 0.05, icon, live, materialName));
        buttons.add(info);
        buttons.add(navButton("back", cx(ITEM_INFO_COLUMN_X), cy(ITEM_BACK_Y), -0.05, Lang.t("gui.back"), e -> openMainMenu(player)));

        // Small action buttons right of the card, on the line they act on: copy the source UUID
        // (sent to chat as a click-to-copy text) and teleport to the item's last known place.
        if (unit.duplicatedFromUuid() != null) {
            String source = unit.duplicatedFromUuid();
            addInfoLineButton(buttons, info, infoLines, Lang.t("gui.item.duplicate-of"), "item-copy-dup", Lang.t("gui.item.copy-uuid"),
                    e -> sendCopyable(player, Lang.t("gui.item.duplicate-of"), source));
        }
        if (canTeleport(location)) {
            addInfoLineButton(buttons, info, infoLines, Lang.t("gui.item.location"), "item-teleport", Lang.t("gui.teleport"),
                    e -> teleportToUnit(player, location));
        }

        // Own panel for the history (titled with its count) and one for the item, so the background
        // reaches around the whole list instead of being cut by a shared box ("rozšíříš to pozadí").
        java.util.function.Predicate<ButtonData> isHistory = b -> b.getId().equals("item-history");
        openScreen(player, buttons, "purrtechlog:detail:" + unit.uuid(),
                new Region(Lang.t("gui.item.history-title") + " §7(" + history.events().size() + ")", isHistory, 0f, ITEM_HISTORY_PANEL_PAD_X),
                new Region(MAIN_TITLE, isHistory.negate()));
    }

    public void openAlerts(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<DupeAlertRecord> alerts = dupeAlertDao.findUnresolved(50);
                Map<String, String> materials = templateDao.findMaterialsByUnits(alerts.stream()
                        .map(DupeAlertRecord::unitUuid).filter(java.util.Objects::nonNull).distinct().toList());
                Bukkit.getScheduler().runTask(plugin, () -> openAlertsPage(player, alerts, materials));
            } catch (SQLException e) {
                logger.severe("Admin GUI alerts lookup selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    /** Alerts list: same flat card list as the item history, top row y and visible rows. */
    private static final double ALERTS_TOP_Y = -1.3;
    private static final int ALERTS_VISIBLE_ROWS = 4;

    /** One card per unresolved alert (same design as the event rows); clicking opens the item's log. */
    private void openAlertsPage(Player player, List<DupeAlertRecord> alerts, Map<String, String> materials) {
        lastPage.put(player.getUniqueId(), () -> openAlerts(player));
        List<ButtonData> rows = new ArrayList<>();
        for (DupeAlertRecord a : alerts) {
            String where = a.world() != null ? a.world() + " " + a.x() + " " + a.y() + " " + a.z() : Lang.t("gui.place-unknown");
            List<String> lines = List.of(
                    BAD + "[" + a.severity() + "] " + VALUE + (a.note() != null ? a.note() : "dupe")
                            + MUTED + "  " + (a.unitUuid() != null ? shortUuid(a.unitUuid()) : "?"),
                    MUTED + formatTime(a.detectedAt()) + "  |  " + LABEL + Lang.t("gui.alerts.found") + " " + VALUE + a.observedCount()
                            + LABEL + "/" + a.expectedAliveCount() + MUTED + "  |  " + where);
            rows.add(eventRowButton("alert-" + a.id(), 0, 0, 0.01, lines, iconFor(materials.get(a.unitUuid())), 0f,
                    e -> {
                        if (a.unitUuid() != null) {
                            openDetail(player, a.unitUuid());
                        }
                    }, null, null));
        }

        List<ButtonData> buttons = new ArrayList<>();
        double rowStepY = 0.65;
        double bottomRowY = ALERTS_TOP_Y + (ALERTS_VISIBLE_ROWS - 1) * rowStepY;
        if (rows.isEmpty()) {
            buttons.add(buildStaticText("alerts-empty", cx(0), cy(0), 0.05, List.of(GOOD + Lang.t("gui.alerts.none"))));
        } else {
            buttons.add(scrollFrame("alerts", cx(0), cy(bottomRowY), FLAT_LIST_Z, rows,
                    EVENT_ROW_CARD_WIDTH_BLOCKS * PIXELS_PER_BLOCK + HITBOX_PADDING_PX, rowStepY, ALERTS_VISIBLE_ROWS));
        }
        buttons.add(navButton("back", cx(0), cy(bottomRowY + 0.5), 0.05, Lang.t("gui.back"), e -> openMainMenu(player)));

        openScreen(player, buttons, "purrtechlog:alerts",
                new Region(Lang.t("gui.main.alerts") + " §7(" + alerts.size() + (alerts.size() >= 50 ? "+" : "") + ")", b -> true));
    }

    /** State of the players menu: the full directory (loaded once per open) plus the live search text. */
    private static final class PlayersSession {
        private final List<PlayerRecord> all;
        private final StringBuilder query = new StringBuilder();
        private int offset;

        PlayersSession(List<PlayerRecord> all) {
            this.all = all;
        }
    }

    private static final List<String> SEARCH_KEYS = List.of(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
            "u", "v", "w", "x", "y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "_");
    private static final int SEARCH_KEY_COLUMNS = 9;

    private final Map<UUID, PlayersSession> playersSessions = new ConcurrentHashMap<>();

    /** Opens the dedicated players menu (fresh session, empty search). */
    public void openPlayerList(Player player, int offset) {
        loadPlayers(player, session -> session.offset = Math.max(0, offset));
    }

    /** Chat-typed search: opens the players menu with the text already in the search field. */
    private void openPlayerSearch(Player player, String query) {
        loadPlayers(player, session -> session.query.append(query.trim()));
    }

    private void loadPlayers(Player player, Consumer<PlayersSession> init) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayersSession session = new PlayersSession(playerDirectory.listPlayers());
                init.accept(session);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    playersSessions.put(player.getUniqueId(), session);
                    openPlayersMenu(player);
                });
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni seznamu hracu selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    /**
     * Players menu: filtered player list on the left, search field + on-screen keyboard on the right.
     * Every keystroke filters the in-memory directory (name or UUID contains the text) and re-opens the page.
     * Layout (rel to screen center, blocks): list rows x -2.6, step 0.45; keyboard x 1.9, step 0.62 x 0.5
     * with 7px (0.44) hitboxes - under both steps; lowest control row y 1.25 (below the +1.5 floor line).
     */
    private void openPlayersMenu(Player player) {
        PlayersSession s = playersSessions.get(player.getUniqueId());
        if (s == null) {
            openPlayerList(player, 0);
            return;
        }
        String q = s.query.toString().toLowerCase(Locale.ROOT);
        List<PlayerRecord> filtered = q.isEmpty() ? s.all : s.all.stream()
                .filter(p -> p.currentName().toLowerCase(Locale.ROOT).contains(q) || p.uuid().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        int offset = filtered.isEmpty() ? 0 : Math.max(0, Math.min(s.offset, filtered.size() - 1));
        offset -= offset % PLAYERS_PAGE_SIZE;
        s.offset = offset;
        int end = Math.min(offset + PLAYERS_PAGE_SIZE, filtered.size());
        final int pageStart = offset;

        List<ButtonData> buttons = new ArrayList<>();
        buttons.add(infoTextButton(cx(0), cy(-2.1), 0.04, List.of(
                Lang.t("gui.players.search", s.query.isEmpty() ? "-" : s.query),
                filtered.isEmpty() ? Lang.t("gui.players.count-none") : Lang.t("gui.players.range", offset + 1, end, filtered.size()))));

        double y = -1.3;
        for (PlayerRecord p : filtered.subList(offset, end)) {
            String label = "[" + Lang.t(p.online() ? "common.online" : "common.offline") + "] " + p.currentName();
            buttons.add(navButton("player-" + p.uuid(), cx(-2.6), cy(y), 0.05, label, e -> openPlayerDetail(player, p.uuid())));
            y += 0.45;
        }
        if (offset > 0) {
            buttons.add(navButton("prev", cx(-3.3), cy(1.25), 0.05, Lang.t("gui.prev"), e -> {
                s.offset = pageStart - PLAYERS_PAGE_SIZE;
                openPlayersMenu(player);
            }));
        }
        if (end < filtered.size()) {
            buttons.add(navButton("next", cx(-1.9), cy(1.25), 0.05, Lang.t("gui.next"), e -> {
                s.offset = pageStart + PLAYERS_PAGE_SIZE;
                openPlayersMenu(player);
            }));
        }

        double stepX = 0.62, startX = 1.9 - (SEARCH_KEY_COLUMNS - 1) * stepX / 2.0;
        for (int i = 0; i < SEARCH_KEYS.size(); i++) {
            String key = SEARCH_KEYS.get(i);
            buttons.add(navButton("skey-" + i, cx(startX + (i % SEARCH_KEY_COLUMNS) * stepX),
                    cy(-1.3 + (i / SEARCH_KEY_COLUMNS) * 0.5), 0.05, 7, 7, key, e -> {
                        if (s.query.length() < 16) s.query.append(key);
                        s.offset = 0;
                        openPlayersMenu(player);
                    }));
        }
        buttons.add(navButton("skey-back", cx(0.9), cy(0.9), 0.05, 24, 7, Lang.t("gui.keyboard.backspace"), e -> {
            if (!s.query.isEmpty()) s.query.setLength(s.query.length() - 1);
            s.offset = 0;
            openPlayersMenu(player);
        }));
        buttons.add(navButton("skey-clear", cx(2.1), cy(0.9), 0.05, 24, 7, Lang.t("gui.keyboard.clear"), e -> {
            s.query.setLength(0);
            s.offset = 0;
            openPlayersMenu(player);
        }));
        buttons.add(navButton("skey-chat", cx(3.3), cy(0.9), 0.05, 24, 7, Lang.t("gui.keyboard.to-chat"), e -> beginPlayerSearch(player)));
        buttons.add(navButton("back", cx(1.9), cy(1.25), 0.05, Lang.t("gui.main-menu"), e -> {
            playersSessions.remove(player.getUniqueId());
            openMainMenu(player);
        }));

        // Page key carries query + offset so every state is a genuinely new menu, never a reused one.
        openScreen(player, buttons, "purrtechlog:players:" + q + ":" + offset);
    }

    public void openPlayerDetail(Player player, String targetUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<PlayerDirectoryService.PlayerProfile> found = playerDirectory.lookupPlayer(targetUuid);
                if (found.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("chat.unknown-player", targetUuid)));
                    return;
                }
                PlayerDirectoryService.PlayerActivity activity = playerDirectory.findActivity(targetUuid, 100);
                Bukkit.getScheduler().runTask(plugin, () -> openPlayerDetailPage(player, found.get(), activity));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail hrace selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void openPlayerDetailPage(Player player, PlayerDirectoryService.PlayerProfile profile,
                                       PlayerDirectoryService.PlayerActivity activity) {
        PlayerRecord unit = profile.player();

        List<String> infoLines = new ArrayList<>();
        infoLines.add(Lang.t("gui.player.nick", unit.currentName()));
        infoLines.add("UUID: " + unit.uuid());
        infoLines.add(unit.online() ? Lang.t("gui.player.online") : Lang.t("gui.player.offline", formatTime(unit.lastSeenAt())));
        infoLines.add(Lang.t("gui.player.first-join", formatTime(unit.firstJoinedAt())));
        String priorNames = profile.nameHistory().stream()
                .map(n -> n.name() + " (" + formatTime(n.changedAt()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse(Lang.t("gui.none"));
        infoLines.add(Lang.t("gui.player.prior-names", priorNames));

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
        buttons.add(scrollListButton("activity", cx(0), cy(0.3), 0.05, activityLines, Lang.t("gui.player.no-activity")));
        buttons.add(navButton("back", cx(0), cy(1.4), -0.05, Lang.t("gui.back"), e -> openMainMenu(player)));

        openScreen(player, buttons, "purrtechlog:player:" + unit.uuid());
    }

    // === Page background: one panel per Region, sized to the buttons it holds (not the whole
    // SCREEN_WIDTH x SCREEN_HEIGHT box, whose bottom sat ~3 blocks below eye level - "zasahuje do
    // bloků"), with its title on top. ===

    private static final String MAIN_TITLE = "PurrTechDetailLogger";
    /** Air around the buttons, and the extra room above them for the title (blocks). */
    private static final double PANEL_MARGIN = 0.3, PANEL_TITLE_ROOM = 0.4;
    private static final float PANEL_TITLE_SCALE = 1.1f;
    /** Lowest a panel may reach, blocks below eye level - about the player's feet. */
    private static final double PANEL_MAX_BOTTOM_REL = 1.45;
    private static final Color PANEL_FALLBACK = Color.fromARGB(100, 15, 15, 15);

    /** A titled background panel around the page buttons {@code members} accepts. */
    /** {@code padX} = extra panel width on each side (blocks), on top of {@link #PANEL_MARGIN}. */
    private record Region(String title, java.util.function.Predicate<ButtonData> members, float rotationY, double padX) {
        Region(String title, java.util.function.Predicate<ButtonData> members) {
            this(title, members, 0f, 0);
        }

        Region(String title, java.util.function.Predicate<ButtonData> members, float rotationY) {
            this(title, members, rotationY, 0);
        }
    }

    /** Opens a page with one titled panel around all its buttons (or the given regions). */
    private void openScreen(Player player, List<ButtonData> buttons, String key, Region... regions) {
        if (regions.length == 0) {
            regions = new Region[]{new Region(MAIN_TITLE, b -> true)};
        }
        List<DisplayLayerData> layers = new ArrayList<>();
        // Invisible sizing layer: the biggest layer still decides screenWidth/Height (where zeroLoc
        // and so every cx()/cy() lands), the panels themselves are sized to their content.
        TextDisplayLayerData sizer = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "bg", 1)
                .setText(List.of(" "))
                .setBackground(TRANSPARENT);
        sizer.setWidth(SCREEN_WIDTH_BLOCKS);
        sizer.setHeight(SCREEN_HEIGHT_BLOCKS);
        layers.add(sizer);
        for (Region region : regions) {
            addPanel(layers, buttons, region);
        }
        // Center of the screen, NOT (0,0) - see [[reference-purrtechdisplaygui-coordinate-rules]].
        PageData page = new PageData(CENTER_X, CENTER_Y, 0, new LayersData(layers, GUI_PATH + ":background", GUI_PATH), PageType.NORMAL);
        // updateMenu, not openMenu: a click that only changes looks (filter toggle, time mode, picker
        // check, search) patches the open menu in place - no respawn, no flicker, list keeps its
        // scroll. A different page (other buttons) still opens fresh.
        DisplayGuiAPI.updateMenu(player, Skin.apply(new ScreenPageData(page, buttons, key, MENU_DISTANCE_PIXELS), null));
    }

    /** Where a region's panel lands: center (page offsets from screen center), size, depth, the
     * title's y, and the turn. {@code null} when the region holds no button. */
    private record PanelBox(double centerX, double centerY, double deep, double width, double height,
                            double titleY, float rotationY) {
        /** World-ish x/z (page offsets) of the panel's left (-1) or right (+1) edge along its face. */
        double edgeX(int side) {
            return centerX + side * width / 2 * Math.cos(Math.toRadians(rotationY));
        }

        double edgeZ(int side) {
            return deep - side * width / 2 * Math.sin(Math.toRadians(rotationY));
        }
    }

    private static PanelBox panelBox(List<ButtonData> buttons, Region region) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double backZ = 0;
        // A turned region's buttons lie on one plane through the first member (z = z0 - along*sin):
        // x below is measured along that plane, and the panel is turned the same way.
        double turn = Math.toRadians(region.rotationY());
        double pivotX = Double.NaN, planeZ = Double.MAX_VALUE;
        for (ButtonData b : buttons) {
            // the hover preview floats far in front of the menu and draws its own panel
            if (EVENT_PREVIEW_ID.equals(b.getId()) || !region.members().test(b)) continue;
            if (Double.isNaN(pivotX)) pivotX = b.getX();
            // a button's visual grows up from its x,y (+y = down) and is centered horizontally
            double w = b.getWidth(), h = b.getHeight();
            if (b instanceof ButtonListScrollButtonData) {
                // a list's hitbox is kept narrower than its rows (scrollFrame) - size by its frame layer
                for (DisplayLayerData l : b.getLayersData().getDisplayLayerData().values()) w = Math.max(w, l.getWidth());
            }
            double along = (b.getX() - pivotX) / Math.cos(turn);
            minX = Math.min(minX, along - w / 2);
            maxX = Math.max(maxX, along + w / 2);
            minY = Math.min(minY, b.getY() - h);
            maxY = Math.max(maxY, b.getY());
            planeZ = Math.min(planeZ, b.getZ() + along * Math.sin(turn));
            // a button turned toward the player pokes its far edge behind its own plane - keep the
            // panel behind that edge so the two never cut through each other
            float rotY = b.getLayersData().getDisplayLayerData().values().stream()
                    .map(DisplayLayerData::getRotationY).filter(r -> r != 0f).findFirst().orElse(0f);
            backZ = Math.min(backZ, b.getZ() - w / 2 * Math.abs(Math.sin(Math.toRadians(rotY))));
        }
        if (minX > maxX) return null;

        double titleWidth = panelTitle(0, 0, 0, 0, region.title()).estimateContentWidthBlocks();

        double top = minY - PANEL_MARGIN - PANEL_TITLE_ROOM;
        double bottom = Math.min(maxY + PANEL_MARGIN, CENTER_Y + PANEL_MAX_BOTTOM_REL);
        double width = Math.max(maxX - minX + 2 * (PANEL_MARGIN + region.padX()), titleWidth + 2 * PANEL_MARGIN);
        // page layers are offsets from the page anchor (= screen center)
        double centerAlong = (minX + maxX) / 2;
        double centerX = pivotX + centerAlong * Math.cos(turn) - CENTER_X;
        double deep = turn == 0 ? backZ - 0.05 : planeZ - centerAlong * Math.sin(turn) - 0.05;
        return new PanelBox(centerX, (top + bottom) / 2 - CENTER_Y, deep, width, bottom - top,
                minY - PANEL_MARGIN + 0.05 - CENTER_Y, region.rotationY());
    }

    /** Panel (ItemsAdder texture, or a dim rectangle without it) + title behind the region's buttons. */
    private static void addPanel(List<DisplayLayerData> layers, List<ButtonData> buttons, Region region) {
        PanelBox box = panelBox(buttons, region);
        if (box == null) return;
        double centerX = box.centerX(), centerY = box.centerY(), deep = box.deep();
        double width = box.width(), height = box.height();

        int position = layers.size() + 1;
        DisplayLayerData panel = Skin.enabled
                ? Skin.texture("display_entity_configuration", centerX, centerY, deep, width, height, position, GUI_PATH, "panel-skin")
                : null;
        if (panel == null) {
            // text grows up from its anchor, so the anchor is the rectangle's bottom edge
            TextDisplayLayerData rect = new TextDisplayLayerData(centerX, centerY + height / 2, deep, GUI_PATH, "panel", position)
                    .setText(List.of(" "))
                    .setBackground(PANEL_FALLBACK);
            rect.setAutoFitText(false);
            rect.setScale(new Vector3f((float) (width / rect.backgroundWidthBlocksAtUnitScale()),
                    (float) (height / rect.backgroundHeightBlocksAtUnitScale()), 1f));
            panel = rect;
        }
        panel.setRotationY(region.rotationY());
        layers.add(panel);
        // title bottom just above the buttons, horizontally on the panel's center
        TextDisplayLayerData title = panelTitle(centerX, box.titleY(), deep + 0.02, position + 1, region.title());
        title.setRotationY(region.rotationY());
        layers.add(title);
    }

    private static TextDisplayLayerData panelTitle(double x, double y, double deep, int position, String title) {
        TextDisplayLayerData text = new TextDisplayLayerData(x, y, deep, GUI_PATH, "panel-title", position,
                TRANSPARENT, TextDisplay.TextAlignment.CENTER)
                .setText(List.of("§6" + title));
        text.setAutoFitText(false);
        text.setTextShadow(true);
        text.setScale(new Vector3f(PANEL_TITLE_SCALE, PANEL_TITLE_SCALE, 1f));
        return text;
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

    /** Button design: just the text - the optional ItemsAdder frame (config {@code gui.textures}) is
     * added for every button at once by {@link Skin#apply} when the screen opens. */
    private LayersData buttonDesign(String id, TextDisplayLayerData text, double widthPixels, float rotationY, boolean stateColour) {
        return new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH);
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick) {
        return navButton(id, x, y, z, label, onClick, false);
    }

    private ButtonData navButton(String id, double x, double y, double z, String label,
                                  Consumer<MenuButtonClickEvent> onClick, boolean active) {
        // Interaction hitbox is auto-sized to the label's own rendered size (see buildTextButton)
        // instead of a hand-picked flat default - matches "design size" per the user's request.
        return buildTextButton(id, x, y, z, label, onClick, active, false, 0f);
    }

    private ButtonData compactButton(String id, double x, double y, double z, String label,
                                      Consumer<MenuButtonClickEvent> onClick, boolean active) {
        // Shrinks labels wider than COMPACT_TARGET_WIDTH_BLOCKS so every button in a packed grid
        // ends up roughly the same visual width regardless of label length - see
        // [[reference-purrtechdisplaygui-coordinate-rules]] for why the events-category grid needed
        // this (long labels at full scale vastly exceeded the grid's column step and stuck together).
        return buildTextButton(id, x, y, z, label, onClick, active, true, 0f);
    }

    /** Same, turned around the Y axis (the left filter column faces the player). */
    private ButtonData compactButton(String id, double x, double y, double z, String label,
                                      Consumer<MenuButtonClickEvent> onClick, boolean active, float rotationY) {
        return buildTextButton(id, x, y, z, label, onClick, active, true, rotationY);
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
                                        Consumer<MenuButtonClickEvent> onClick, boolean active, boolean compact,
                                        float rotationY) {
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
        if (rotationY != 0f) {
            text.setRotationY(rotationY); // own statement: returns the base type, see eventRowButton
        }
        double widthPixels = Math.max(HITBOX_MIN_WIDTH_PX,
                text.estimateContentWidthBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
        double heightPixels = HITBOX_HEIGHT_PX;
        LayersData design = buttonDesign(id, text, widthPixels, rotationY, active);
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(design)
                .id(id)
                .onLeftClick(onClick)
                // A rotated button's hitbox is tiled along the tilted face (0.5-block tiles), so it is
                // recessed by half a tile; an unrotated one is a single box recessed by half its width.
                .hitboxOffsetZ(rotationY != 0f ? ROTATED_TILE_HITBOX_RECESS_Z : hitboxRecessZ(widthPixels))
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
        LayersData design = buttonDesign(id, text, widthPixels, 0f, !valid);
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
        LayersData design = buttonDesign(id, text, widthPixels, 0f, active);
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
        return infoTextButton(x, y, z, lines, 0f);
    }

    private ButtonData infoTextButton(double x, double y, double z, List<String> lines, float rotationY) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, "info-text", 1)
                .setText(lines)
                .setBackground(PANEL_BACKGROUND);
        if (rotationY != 0f) {
            text.setRotationY(rotationY);
        }
        return textBlock("info", x, y, z, text);
    }

    /** Wrap width (characters) and scale of a designed info card - see {@link #infoCard}. */
    private static final int INFO_CARD_WRAP_CHARS = 44;
    private static final float INFO_CARD_SCALE = 0.75f;

    /** A multi-section info text (§-coloured lines, blank lines between sections) drawn as designed:
     * DisplayGUI's default shrinks anything over 3 lines (to 65 % here) and wraps at 32 chars. */
    private ButtonData infoCard(String id, double x, double y, double z, List<String> lines) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-text", 1,
                PANEL_BACKGROUND, TextDisplay.TextAlignment.LEFT)
                .setText(lines)
                .setMaxLines(lines.size() * 2)
                .setMaxCharactersPerLine(INFO_CARD_WRAP_CHARS);
        text.setTextShadow(true);
        text.setScale(new Vector3f(INFO_CARD_SCALE, INFO_CARD_SCALE, 1f));
        return textBlock(id, x, y, z, text);
    }

    /** Non-clickable text button sized to the text's own rendered box, so the page panel fits it
     * (a fixed 30 x 10 px left long texts sticking out of the panel). */
    private ButtonData textBlock(String id, double x, double y, double z, TextDisplayLayerData text) {
        double widthPixels = text.estimateContentWidthBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX;
        double heightPixels = text.estimateContentHeightBlocks() * PIXELS_PER_BLOCK;
        return ButtonData.builder()
                .at(x, y, z)
                .size(widthPixels, heightPixels)
                .layers(new LayersData(List.of(text), GUI_PATH + ":" + id, GUI_PATH))
                .id(id)
                .hitboxOffsetZ(text.getRotationY() != 0f ? ROTATED_TILE_HITBOX_RECESS_Z : hitboxRecessZ(widthPixels))
                .build();
    }

    /**
     * The item page's big item, with a label under it that shows the item's material + display
     * name while hovered ("když na něj najedu tak mi to bude psát ten daný item a display name") -
     * DisplayGUI has no hover-only layer, so the label is swapped in/out live via
     * {@link #findOpenButton}. Label is always present (blank when not hovered) so both designs
     * share the same layer positions, which {@link Button#updateButton(ButtonData)} requires.
     */
    private ButtonData itemIconButton(Player player, double x, double y, double z, ItemStack icon,
                                      ItemStack live, String materialName) {
        String name = live != null ? itemLabel(live) : null;
        List<String> label = new ArrayList<>();
        label.add(live != null ? live.getType().name() : materialName != null ? materialName : "?");
        label.add(name != null && !name.equals(live.getType().name()) ? name : Lang.t("gui.item.no-display-name"));

        double sizePixels = ITEM_ICON_BLOCKS * PIXELS_PER_BLOCK;
        return ButtonData.builder()
                .at(x, y, z)
                .size(sizePixels, sizePixels)
                .layers(itemIconDesign(icon, List.of(" "), TRANSPARENT))
                .id(ITEM_ICON_ID)
                .onHoverStart(ctx -> updateItemIcon(player, icon, label, PANEL_BACKGROUND))
                .onHoverEnd(ctx -> updateItemIcon(player, icon, List.of(" "), TRANSPARENT))
                .hitboxOffsetZ(hitboxRecessZ(sizePixels))
                .build();
    }

    private LayersData itemIconDesign(ItemStack icon, List<String> label, Color labelBackground) {
        // Item sits above the button's base (its hitbox grows upward from there); the label
        // hangs below it (+y = down, and a text display grows upward from its anchor).
        ItemDisplayLayerData item = new ItemDisplayLayerData(0, -ITEM_ICON_BLOCKS / 2.0, 0, GUI_PATH, ITEM_ICON_ID + "-item", 1)
                .setItemStack(icon);
        float scale = (float) ITEM_ICON_BLOCKS;
        item.setScale(new Vector3f(scale, scale, scale));
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0.6, 0.02, GUI_PATH, ITEM_ICON_ID + "-label", 2)
                .setText(label)
                .setBackground(labelBackground);
        return new LayersData(List.of(item, text), GUI_PATH + ":" + ITEM_ICON_ID, GUI_PATH);
    }

    private void updateItemIcon(Player player, ItemStack icon, List<String> label, Color labelBackground) {
        Button button = findOpenButton(player, ITEM_ICON_ID);
        if (button != null) {
            button.updateButton(ButtonData.builder()
                    .layers(itemIconDesign(icon, label, labelBackground))
                    .id(ITEM_ICON_ID)
                    .build());
        }
    }


    /** Rows a text scroll list shows at once (nearby players, player activity). */
    private static final int TEXT_LIST_ROWS = 4;

    private ButtonData scrollListButton(String id, double x, double y, double z, List<String> items, String emptyLabel) {
        // Sized to what is actually drawn (TEXT_LIST_ROWS lines, widest item) - it used to be a fixed
        // 20 x 130 px, and the page panel sized itself to that 8-block-tall box ("moc vysoké").
        List<String> shown = items.isEmpty() ? List.of(emptyLabel) : items;
        TextDisplayLayerData probe = listContent(id).setText(shown);
        double widthBlocks = probe.estimateContentWidthBlocks();
        probe.setText(java.util.Collections.nCopies(TEXT_LIST_ROWS, "x"));
        return scrollListButton(id, x, y, z, widthBlocks * PIXELS_PER_BLOCK + HITBOX_PADDING_PX,
                probe.estimateContentHeightBlocks() * PIXELS_PER_BLOCK, TEXT_LIST_ROWS, items, emptyLabel);
    }

    /** A text list's content layer; maxLines = the window, so DisplayGUI doesn't shrink 4 rows to 3/4. */
    private static TextDisplayLayerData listContent(String id) {
        return new TextDisplayLayerData(0, 0, 0, GUI_PATH, id + "-content", 1)
                .setMaxLines(TEXT_LIST_ROWS)
                .setBackground(PANEL_BACKGROUND);
    }

    private ButtonData scrollListButton(String id, double x, double y, double z, double widthPixels,
                                         double heightPixels, int visibleRows, List<String> items, String emptyLabel) {
        TextDisplayLayerData content = listContent(id);
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

    /** How far (blocks) the list rows' visuals sit behind where DisplayGUI puts them. The list pushes
     * every row 0.5 block toward the player so the row hitboxes win over the list's own - that left the
     * rows floating well in front of the list frame ("moc vepredu"). The visuals are pulled back by this
     * much, and the row hitboxes are recessed by the same amount so they stay on the visual. */
    private static final double EVENTS_ROW_PULL_BACK_BLOCKS = 0.3;
    private static final double EVENTS_ROW_HITBOX_RECESS_Z =
            ROTATED_TILE_HITBOX_RECESS_Z - EVENTS_ROW_PULL_BACK_BLOCKS * PIXELS_PER_BLOCK;

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


    // === Events browser: all events, newest first, filterable by category and time range. ===

    public void openEventsPage(Player player) {
        lastPage.put(player.getUniqueId(), () -> openEventsPage(player));
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        List<String> types = List.copyOf(filter.activeCategories);
        List<String> playerUuids = List.copyOf(filter.activePlayers);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<EventRecord> events = eventDao.findFiltered(types, playerUuids, filter.from, filter.to,
                        EVENTS_LIST_LIMIT);
                // Row icons - one batched lookup instead of one query per row.
                Map<String, String> materials = templateDao.findMaterialsByUnits(events.stream()
                        .map(EventRecord::unitUuid).filter(java.util.Objects::nonNull).distinct().toList());
                Bukkit.getScheduler().runTask(plugin, () -> openEventsListPage(player, filter, events, materials));
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni udalosti selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void openEventsListPage(Player player, EventsFilter filter, List<EventRecord> events,
                                    Map<String, String> materials) {
        List<ButtonData> buttons = new ArrayList<>();
        resetEventPreview(player);
        unitPreviewCache.clear();

        // Filter grid: built at a provisional spot, moved next to the main panel at the end.
        addFilterGrid(buttons, player, filter, -4.0, 0.05);

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
        double rowStartY = -1.3; // was -1.0: raised 0.3 ("zvedni tak o 0.3")
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

        // Player filter: a button above the time button that opens a separate menu listing everyone with a
        // record (see openPlayerPicker). Lit green while any player is checked.
        buttons.add(navButton("players-toggle", cx(0), cy(-0.25), 0.05,
                filter.activePlayers.isEmpty() ? Lang.t("gui.events.players-all") : Lang.t("gui.events.players-n", filter.activePlayers.size()),
                e -> {
                    filter.pickerOffset = 0;
                    openPlayerPicker(player);
                }, !filter.activePlayers.isEmpty()));

        // Time-display-mode toggle ("určí si hráč ve filtru") + date range, stacked vertically in
        // the center column - opens the small in-game calendar instead of a chat prompt.
        buttons.add(navButton("time-mode", cx(0), cy(0.1), 0.05,
                Lang.t(filter.relativeTime ? "gui.events.time-relative" : "gui.events.time-date"), e -> {
                    filter.relativeTime = !filter.relativeTime;
                    openEventsPage(player);
                }));
        double dateY = 0.5;
        double dateStepY = 0.35;
        buttons.add(navButton("date-from", cx(0), cy(dateY), 0.05,
                Lang.t("gui.events.from", filter.from != null ? formatTime(filter.from) : "-"), e -> openEventsCalendar(player, true)));
        buttons.add(navButton("date-to", cx(0), cy(dateY + dateStepY), 0.05,
                Lang.t("gui.events.to", filter.to != null ? formatTime(filter.to) : "-"), e -> openEventsCalendar(player, false)));
        double afterDateY = dateY + dateStepY;
        if (filter.from != null || filter.to != null) {
            afterDateY += dateStepY;
            buttons.add(navButton("date-clear", cx(0), cy(afterDateY), 0.05, Lang.t("gui.events.clear-range"), e -> {
                filter.from = null;
                filter.to = null;
                openEventsPage(player);
            }));
        }

        buttons.add(navButton("back", cx(0), cy(afterDateY + 0.25), 0.05, Lang.t("gui.main-menu"), e -> openMainMenu(player)));

        String pageKey = "purrtechlog:events:" + (filter.activeCategories.isEmpty()
                ? "all" : String.join("+", filter.activeCategories))
                + ":" + (filter.activePlayers.isEmpty() ? "everyone" : String.join("+", filter.activePlayers));
        // Filter column gets its own panel; the main one only holds the center column - the list
        // rows carry their own backgrounds, and a panel reaching behind the turned filter/list
        // cut through them ("zasahuje do seznamu událostí a filtru").
        // The old "Kategorie / Zaznamu" info box is gone ("klidně i odebral"); the count lives in the title.
        java.util.function.Predicate<ButtonData> inFilter = b -> b.getId().startsWith("cat-");
        String filterTitle = Lang.t("gui.events.filter-title", events.size() + (events.size() >= EVENTS_LIST_LIMIT ? "+" : ""));
        Region main = new Region(MAIN_TITLE, inFilter.negate().and(b -> !b.getId().equals("events-list")));
        Region filterRegion = new Region(filterTitle, inFilter, EVENTS_FILTER_ROTATION_Y_DEGREES);
        // Slide the turned filter (X and Z together) so its right edge meets the main panel's left
        // edge, instead of sitting back by the wall ("pravý roh ... stejně s levým rohem").
        PanelBox mainBox = panelBox(buttons, main), filterBox = panelBox(buttons, filterRegion);
        if (mainBox != null && filterBox != null) {
            double dx = mainBox.edgeX(-1) - EVENTS_FILTER_GAP_BLOCKS - filterBox.edgeX(1);
            double dz = mainBox.edgeZ(-1) - filterBox.edgeZ(1);
            buttons.removeIf(inFilter);
            addFilterGrid(buttons, player, filter, -4.0 + dx, 0.05 + dz);
        }
        openScreen(player, buttons, pageKey, main, filterRegion);
    }

    /**
     * The category filter: 2 columns x 7 rows of compact buttons on ONE plane turned toward the player
     * (outer column forward) - turning each button in place staggered the columns ("vyrovnej to s
     * tím druhým sloupcem"). {@code centerX}/{@code baseZ} = the plane's center (relX, z).
     */
    private void addFilterGrid(List<ButtonData> buttons, Player player, EventsFilter filter, double centerX, double baseZ) {
        List<String> categoryButtons = new ArrayList<>();
        categoryButtons.add("ALL");
        categoryButtons.addAll(EVENT_CATEGORIES);
        int columns = 2;
        double stepX = 2.0, startY = -1.3, stepY = 0.4;
        double turn = Math.toRadians(EVENTS_FILTER_ROTATION_Y_DEGREES);
        for (int i = 0; i < categoryButtons.size(); i++) {
            String type = categoryButtons.get(i);
            double along = (i % columns - (columns - 1) / 2.0) * stepX;
            double x = centerX + along * Math.cos(turn);
            double z = baseZ - along * Math.sin(turn);
            double y = startY + (i / columns) * stepY;
            boolean active = type.equals("ALL") ? filter.activeCategories.isEmpty() : filter.activeCategories.contains(type);
            String label = type.equals("ALL") ? Lang.t("gui.events.all") : categoryLabel(type);
            buttons.add(compactButton("cat-" + type, cx(x), cy(y), z, label, e -> toggleCategory(player, type), active,
                    EVENTS_FILTER_ROTATION_Y_DEGREES));
        }
    }

    private void toggleCategory(Player player, String type) {
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        if (type.equals("ALL")) {
            filter.activeCategories.clear(); // "Vse" = no category checked
        } else if (!filter.activeCategories.remove(type)) {
            filter.activeCategories.add(type);
        }
        if (filter.activeCategories.size() == EVENT_CATEGORIES.size()) {
            filter.activeCategories.clear(); // every box checked is the same as "Vse"
        }
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
                                       float rotationY, Consumer<MenuButtonClickEvent> onClick,
                                       Consumer<MenuActionContext> onHoverStart, Consumer<MenuActionContext> onHoverEnd) {
        // One card design for every row ("univerzální design"): same-size card, the item in a slot
        // frame on the left, two left-aligned lines beside it. Offsets go through placeTurned, so the
        // parts stay on the turned row face (layers rotate around their own anchor).
        double w = EVENT_ROW_CARD_WIDTH_BLOCKS, h = EVENT_ROW_CARD_HEIGHT_BLOCKS;
        double back = -EVENTS_ROW_PULL_BACK_BLOCKS;
        double slotX = -w / 2 + EVENT_ROW_PAD_BLOCKS + EVENT_ROW_SLOT_BLOCKS / 2;

        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, back, GUI_PATH, id + "-text", 1,
                TRANSPARENT, TextDisplay.TextAlignment.LEFT)
                .setText(lines);
        text.setAutoFitText(false);
        text.setTextShadow(true);
        double textLeft = slotX + EVENT_ROW_SLOT_BLOCKS / 2 + EVENT_ROW_PAD_BLOCKS;
        double room = w / 2 - EVENT_ROW_PAD_BLOCKS - textLeft;
        double natural = text.estimateContentWidthBlocks();
        if (natural > room) {
            float scale = (float) Math.max(COMPACT_MIN_TEXT_SCALE, room / natural);
            text.setScale(new Vector3f(scale, scale, 1f));
        }
        double tw = text.estimateContentWidthBlocks(), th = text.estimateContentHeightBlocks();
        placeTurned(text, rotationY, textLeft + tw / 2, (h - th) / 2);

        ItemDisplayLayerData iconLayer = new ItemDisplayLayerData(0, 0, back, GUI_PATH, id + "-icon", 2)
                .setItemStack(icon);
        iconLayer.setScale(new Vector3f((float) EVENT_ROW_ICON_BLOCKS, (float) EVENT_ROW_ICON_BLOCKS, (float) EVENT_ROW_ICON_BLOCKS));
        placeTurned(iconLayer, rotationY, slotX, h / 2);

        List<DisplayLayerData> layers = new ArrayList<>(List.of(text, iconLayer));
        // the "-skin" id also tells Skin.apply this row draws its own background
        DisplayLayerData card = Skin.enabled
                ? Skin.texture("cme_button", 0, 0, back - 0.02, w, h, 3, GUI_PATH, id + "-skin")
                : null;
        if (card == null) {
            TextDisplayLayerData rect = new TextDisplayLayerData(0, 0, back - 0.02, GUI_PATH, id + "-skin", 3)
                    .setText(List.of(" "))
                    .setBackground(BUTTON_BACKGROUND);
            rect.setAutoFitText(false);
            rect.setScale(new Vector3f((float) (w / rect.backgroundWidthBlocksAtUnitScale()),
                    (float) (h / rect.backgroundHeightBlocksAtUnitScale()), 1f));
            card = placeTurned(rect, rotationY, 0, 0); // text grows up from its anchor = card bottom
        } else {
            placeTurned(card, rotationY, 0, h / 2);
        }
        layers.add(card);
        ItemDisplayLayerData slot = Skin.enabled
                ? Skin.texture("is_shape_icon", 0, 0, back - 0.01, EVENT_ROW_SLOT_BLOCKS, EVENT_ROW_SLOT_BLOCKS, 4, GUI_PATH, id + "-slot")
                : null;
        if (slot != null) {
            layers.add(placeTurned(slot, rotationY, slotX, h / 2));
        }

        double widthPixels = w * PIXELS_PER_BLOCK;
        double heightPixels = h * PIXELS_PER_BLOCK;
        LayersData design = new LayersData(layers, GUI_PATH + ":" + id, GUI_PATH);
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
                // a flat row is one Interaction as deep as it is wide - recess it like the picker rows
                .hitboxOffsetZ(rotationY != 0f ? EVENTS_ROW_HITBOX_RECESS_Z
                        : hitboxRecessZ(widthPixels) - EVENTS_ROW_PULL_BACK_BLOCKS * PIXELS_PER_BLOCK)
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
        return eventsListButton(player, "events-list", x, y, z, events, materials, filter.relativeTime,
                rowSpacingBlocks, EVENTS_LIST_ROTATION_Y_DEGREES, true);
    }

    /**
     * @param listId       button id - distinct per page (the item page reuses this for its history)
     * @param hoverPreview whether hovering a row drives the events page's item preview panel
     */
    private ButtonData eventsListButton(Player player, String listId, double x, double y, double z,
                                         List<EventRecord> events, Map<String, String> materials,
                                         boolean relativeTime, double rowSpacingBlocks, float rotationY,
                                         boolean hoverPreview) {
        List<ButtonData> rows = new ArrayList<>();
        for (EventRecord ev : events) {
            // Shulker sessions carry the shulker's own color/material in their detail - a placed
            // shulker has no unit, and a held one's anchor template is just plain SHULKER_BOX.
            String materialName = ShulkerSessionLog.EVENT_TYPE.equals(ev.eventType())
                    ? ShulkerSessionLog.shulkerMaterial(ev.detail())
                    : ev.unitUuid() != null ? materials.get(ev.unitUuid()) : null;
            ItemStack icon = iconFor(materialName);
            rows.add(eventRowButton("event-" + ev.id(), 0, 0, 0.01,
                    eventRowLines(ev, relativeTime), icon, rotationY, e -> openEventDetail(player, ev.id()),
                    hoverPreview ? ctx -> showEventPreview(player, ev, icon) : null,
                    hoverPreview ? ctx -> scheduleEventPreviewHide(player) : null));
        }

        double widthPixels = EVENT_ROW_CARD_WIDTH_BLOCKS * PIXELS_PER_BLOCK + HITBOX_PADDING_PX;
        return scrollFrame(listId, x, y, z, rows, widthPixels, rowSpacingBlocks);
    }

    /**
     * The shared part of every scroll list in this GUI: the dark frame behind the visible rows and
     * the {@code button_list_scroll} container holding {@code rows}. {@code x,y,z} anchors the BOTTOM
     * visible row. Used by the events list, the item history and the player filter.
     */
    private ButtonListScrollButtonData scrollFrame(String listId, double x, double y, double z,
                                                  List<ButtonData> rows, double widthPixels,
                                                  double rowSpacingBlocks) {
        return scrollFrame(listId, x, y, z, rows, widthPixels, rowSpacingBlocks, EVENTS_VISIBLE_ROWS);
    }

    private ButtonListScrollButtonData scrollFrame(String listId, double x, double y, double z,
                                                  List<ButtonData> rows, double widthPixels,
                                                  double rowSpacingBlocks, int visibleRows) {
        double heightPixels = visibleRows * rowSpacingBlocks * PIXELS_PER_BLOCK;

        // Frame/background panel spans the whole visible column, vertically centered on it - since
        // this button's own x,y anchors the BOTTOM row (see javadoc above), the frame sits
        // half the column's height ABOVE that anchor, i.e. a negative local y in this GUI's
        // "+y = down" convention (see cy()). No real text content to auto-fit to (this is a blank
        // backdrop, not a label), so width/height/scale are set explicitly instead of relying on
        // TextDisplayLayerData's normal auto-fit-to-text sizing - same technique as the real
        // DisplayGUI source's own list-frame example (Internal.commands.TestCommand, "playerlist").
        TextDisplayLayerData frame = new TextDisplayLayerData(
                0, -(visibleRows * rowSpacingBlocks) / 2.0, 0, GUI_PATH, listId + "-frame", 0)
                .setText(List.of(" "))
                .setBackground(PANEL_BACKGROUND);
        frame.setAutoFitText(false);
        frame.setScale(frame.getVectorWithPixels(widthPixels, heightPixels, 1));
        frame.setWidth((float) (widthPixels / PIXELS_PER_BLOCK));
        frame.setHeight((float) (heightPixels / PIXELS_PER_BLOCK));
        LayersData frameLayers = new LayersData(List.of(frame), GUI_PATH + ":" + listId, GUI_PATH);

        // Scroll hitbox: narrower than the frame and recessed so its front face is flush with the
        // list plane - the (unrecessed) row hitboxes then sit fully in front of it.
        double scrollHitboxWidthPixels = Math.max(HITBOX_MIN_WIDTH_PX, widthPixels - EVENTS_LIST_SCROLL_HITBOX_SHRINK_PX);
        return ButtonListScrollButtonData.buttonListScrollBuilder()
                .at(x, y, z)
                .size(scrollHitboxWidthPixels, heightPixels)
                .hitboxOffsetZ(hitboxRecessZ(scrollHitboxWidthPixels))
                .layers(frameLayers)
                .id(listId)
                .items(rows)
                .visibleRows(visibleRows)
                .rowSpacing(rowSpacingBlocks)
                .build();
    }

    // === Player picker: its own menu (opened from the "Hraci" button on the events page) listing everyone
    // with a record, any number of whom can be checked. ===

    // 6 rows x 0.45 -> 5 x 0.6: the rows sat too close together ("seznam hráčů je moc blízko");
    // the list grows upward from its bottom row, so the top moved up and the page title with it
    // (the panel puts the title above whatever is highest).
    private static final int PICKER_VISIBLE_ROWS = 5;
    private static final double PICKER_ROW_STEP = 0.6;
    private static final double PICKER_WIDTH_BLOCKS = 2.6;
    private static final double PICKER_TOP_Y = -1.7;
    /** Depth of a NOT turned list (player picker, item history; blocks, + = toward the player). Was EVENTS_LIST_Z (0.75), which with
     * the list's +0.5 row push and -0.3 pull-back left the rows ~0.95 in front ("moc vyčuhuje");
     * -0.15 puts them flush with the side buttons (0.05). Bigger = closer. */
    private static final double FLAT_LIST_Z = -0.15;
    /** Side columns (info left, search right): center offset from the list's center. */
    private static final double PICKER_SIDE_X = 3.0;
    private static final double PICKER_SIDE_Y = -0.6;

    public void openPlayerPicker(Player player) {
        EventsFilter filter = eventsFilters.computeIfAbsent(player.getUniqueId(), id -> new EventsFilter());
        lastPage.put(player.getUniqueId(), () -> openPlayerPicker(player));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<EventDao.PlayerOption> players = eventDao.findPlayersWithEvents();
                Bukkit.getScheduler().runTask(plugin, () -> openPlayerPickerPage(player, filter, players));
            } catch (SQLException e) {
                logger.severe("Admin GUI nacteni hracu selhalo: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void openPlayerPickerPage(Player player, EventsFilter filter, List<EventDao.PlayerOption> players) {
        List<ButtonData> buttons = new ArrayList<>();
        // Rows can't see the list they are in (it is built after them), and the list's scroll position
        // lives on its data object - so a click reads it from here, to reopen at the same spot.
        java.util.concurrent.atomic.AtomicReference<ButtonListScrollButtonData> listRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        List<ButtonData> rows = new ArrayList<>();
        String query = filter.pickerQuery != null ? filter.pickerQuery.toLowerCase(java.util.Locale.ROOT) : null;
        for (EventDao.PlayerOption option : players) {
            String uuid = option.uuid();
            boolean checked = filter.activePlayers.contains(uuid);
            String name = option.name() != null ? option.name() : uuid.substring(0, 8);
            if (query != null && !name.toLowerCase(java.util.Locale.ROOT).contains(query) && !uuid.startsWith(query)) {
                continue;
            }
            TextDisplayLayerData text = new TextDisplayLayerData(0, 0, -EVENTS_ROW_PULL_BACK_BLOCKS, GUI_PATH,
                    "player-" + uuid + "-text", 1)
                    .setText(List.of((checked ? "[x] " : "[ ] ") + name))
                    .setBackground(checked ? BUTTON_BACKGROUND_ACTIVE : BUTTON_BACKGROUND);
            text.setAutoFitText(false);
            double natural = text.estimateContentWidthBlocks();
            if (natural > PICKER_WIDTH_BLOCKS) {
                float scale = (float) Math.max(COMPACT_MIN_TEXT_SCALE, PICKER_WIDTH_BLOCKS / natural);
                text.setScale(new Vector3f(scale, scale, 1f));
            }
            double widthPixels = Math.max(HITBOX_MIN_WIDTH_PX,
                    text.estimateContentWidthBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX);
            rows.add(ButtonData.builder()
                    .at(0, 0, 0.01)
                    .size(widthPixels, Math.max(HITBOX_HEIGHT_PX,
                            text.estimateContentHeightBlocks() * PIXELS_PER_BLOCK + HITBOX_PADDING_PX))
                    .layers(new LayersData(List.of(text), GUI_PATH + ":player-" + uuid, GUI_PATH))
                    .id("player-" + uuid)
                    .onLeftClick(e -> {
                        if (!filter.activePlayers.remove(uuid)) {
                            filter.activePlayers.add(uuid);
                        }
                        ButtonListScrollButtonData list = listRef.get();
                        filter.pickerOffset = list != null ? list.getCurrentOffset() : 0;
                        openPlayerPicker(player);
                    })
                    // Not rotated (the menu is centered): one Interaction, recessed by half its width
                    // and by the same pull-back as the visual, so it stays on the text and in front of
                    // the list's own scroll hitbox.
                    .hitboxOffsetZ(hitboxRecessZ(widthPixels) - EVENTS_ROW_PULL_BACK_BLOCKS * PIXELS_PER_BLOCK)
                    .build());
        }

        double bottomRowY = PICKER_TOP_Y + (PICKER_VISIBLE_ROWS - 1) * PICKER_ROW_STEP;
        if (rows.isEmpty()) {
            buttons.add(buildStaticText("players-empty", cx(0), cy(0), 0.05,
                    List.of(Lang.t(query != null ? "gui.picker.no-match" : "gui.picker.empty"))));
        } else {
            ButtonListScrollButtonData list = scrollFrame("players-list", cx(0), cy(bottomRowY), FLAT_LIST_Z, rows,
                    (PICKER_WIDTH_BLOCKS + 0.2) * PIXELS_PER_BLOCK, PICKER_ROW_STEP, PICKER_VISIBLE_ROWS);
            list.setCurrentOffset(filter.pickerOffset); // clamped to the list's range by DisplayGUI
            listRef.set(list);
            buttons.add(list);
        }

        // Explanation beside the list (was above it, under the page title).
        List<String> infoLines = new ArrayList<>(Lang.list("gui.picker.help"));
        infoLines.add("");
        infoLines.add(filter.activePlayers.isEmpty() ? Lang.t("gui.picker.selected-none") : Lang.t("gui.picker.selected-n", filter.activePlayers.size()));
        buttons.add(buildStaticText("players-info", cx(-PICKER_SIDE_X), cy(PICKER_SIDE_Y), 0.05, infoLines));
        buttons.add(navButton("players-search", cx(PICKER_SIDE_X), cy(PICKER_SIDE_Y - 0.2), 0.05,
                query != null ? Lang.t("gui.picker.searching", filter.pickerQuery) : Lang.t("gui.picker.search"), e -> beginPickerSearch(player), query != null));
        if (query != null) {
            buttons.add(navButton("players-search-clear", cx(PICKER_SIDE_X), cy(PICKER_SIDE_Y + 0.2), 0.05, Lang.t("gui.picker.clear-search"), e -> {
                filter.pickerQuery = null;
                filter.pickerOffset = 0;
                openPlayerPicker(player);
            }));
        }
        buttons.add(navButton("players-clear", cx(-1.3), cy(bottomRowY + 0.55), 0.05, Lang.t("gui.picker.everyone"), e -> {
            filter.activePlayers.clear();
            filter.pickerOffset = 0;
            openPlayerPicker(player);
        }, filter.activePlayers.isEmpty()));
        buttons.add(navButton("players-done", cx(1.3), cy(bottomRowY + 0.55), 0.05, Lang.t("gui.done"), e -> {
            filter.pickerOffset = 0;
            filter.pickerQuery = null;
            openEventsPage(player);
        }));

        openScreen(player, buttons, "purrtechlog:players:" + String.join("+", filter.activePlayers));
    }

    /** Row text for {@link #eventRowButton}: line 1 = ID (left-most - "ID se bude ukazovat na levé
     * straně aby se to odlišilo") + time (absolute or "pred X", per {@link EventsFilter#relativeTime})
     * + "Player: name" + what happened; line 2 = world + coords, or a placeholder when the event
     * carries no location. */
    private List<String> eventRowLines(EventRecord e, boolean relativeTime) {
        String time = relativeTime ? EventLineFormatter.formatTimeAgo(e.timestamp()) : formatTime(e.timestamp());
        String who = e.playerUuid() != null ? resolvePlayerAlias(e.playerUuid()) : "?";
        String action = categoryLabel(e.eventType());
        String where = e.world() != null ? e.world() + " " + e.x() + " " + e.y() + " " + e.z() : Lang.t("gui.world-unknown");
        return List.of(
                actionColour(e.eventType()) + action + MUTED + "  #" + e.id() + LABEL + "  " + who,
                MUTED + time + "  |  " + where);
    }

    /** Red for an item leaving the world, green for one coming in, white otherwise. */
    private static String actionColour(String eventType) {
        return switch (eventType) {
            case "DESTROYED", "CREATIVE_DUPLICATE", "VANISHED_BY_COMMAND" -> BAD;
            case "GENESIS", "SPAWNED", "REAPPEARED_AFTER_COMMAND" -> GOOD;
            default -> VALUE;
        };
    }

    // === Events page hover item preview - hovering a row shows the item it's about in a panel
    // right of the list ("Ukazuj ty itemy vpravo"); it lingers 2 s after leaving the row so the
    // player can move onto it, and clicking it opens that item's detail + full history. ===

    /** Rendered size (blocks) of each row's item icon - see {@link #eventRowButton}. */
    private static final double EVENT_ROW_ICON_BLOCKS = 0.45;
    /** Every row's card (blocks): same size for all rows, so the list reads as one column of cards.
     * Height must stay under the list's row step (0.65 on the events page). */
    private static final double EVENT_ROW_CARD_WIDTH_BLOCKS = EVENTS_ROW_TARGET_WIDTH_BLOCKS + EVENT_ROW_ICON_BLOCKS;
    private static final double EVENT_ROW_CARD_HEIGHT_BLOCKS = 0.58;
    /** Item slot frame on the card's left, and the air between card edge / slot / text. */
    private static final double EVENT_ROW_SLOT_BLOCKS = 0.5, EVENT_ROW_PAD_BLOCKS = 0.06;
    private static final String EVENT_PREVIEW_ID = "event-preview";
    /** Preview panel center, relative to the list column's center: half the widest row
     * (text + icon), plus a small gap, plus half the panel. Not yet confirmed in-game. */
    // Was 3.8 - moved closer to the list ("dej více blíže"); the stronger tilt below also narrows
    // how wide the panel looks, so it fits closer without touching the rows.
    private static final double EVENT_PREVIEW_OFFSET_X = 2.0;
    private static final double EVENT_PREVIEW_WIDTH_BLOCKS = 3.2;
    private static final double EVENT_PREVIEW_HEIGHT_BLOCKS = 3.25; // = the list's 5 rows x 0.65
    private static final double EVENT_PREVIEW_ITEM_BLOCKS = 0.55; // inside the 0.8-block item frame
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
                .layers(eventPreviewDesign(PreviewContent.HIDDEN))
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

    /** Preview layout (blocks, panel-local: x right of the panel's center, y UP from its bottom edge). */
    private static final double PREVIEW_PAD = 0.18;
    private static final double PREVIEW_ICON_FRAME_BLOCKS = 0.8;
    private static final double PREVIEW_HEADER_HEIGHT = 0.75;
    private static final float PREVIEW_MIN_TEXT_SCALE = 0.35f;

    /** One preview's content; {@code item == null} = hidden (blank, no background). */
    private record PreviewContent(ItemStack item, String title, String subtitle, List<String> body) {
        static final PreviewContent HIDDEN = new PreviewContent(null, " ", " ", List.of(" "));
    }

    /**
     * The hover preview, every layer at a fixed position so {@link Button#updateButton(ButtonData)}
     * can swap the content in place: 1 body text, 2 item, 3 header (name + material) right of the
     * item, 4 item frame, 5 panel. Panel and frame are only drawn while something is shown
     * ("dej to tam jen když se fakt objeví ten item"). All layers sit at the button origin and are
     * placed by a translation turned with the panel, so the whole panel tilts as one piece.
     */
    private LayersData eventPreviewDesign(PreviewContent content) {
        boolean shown = content.item() != null;
        double w = EVENT_PREVIEW_WIDTH_BLOCKS, h = EVENT_PREVIEW_HEIGHT_BLOCKS;
        double iconX = -w / 2 + PREVIEW_PAD + PREVIEW_ICON_FRAME_BLOCKS / 2;
        double iconY = h - PREVIEW_PAD - PREVIEW_ICON_FRAME_BLOCKS / 2;

        ItemDisplayLayerData itemLayer = new ItemDisplayLayerData(0, 0, 0, GUI_PATH, EVENT_PREVIEW_ID + "-item", 2)
                .setItemStack(shown ? content.item() : new ItemStack(Material.AIR));
        float itemScale = (float) EVENT_PREVIEW_ITEM_BLOCKS;
        itemLayer.setScale(new Vector3f(itemScale, itemScale, itemScale));
        previewPlace(itemLayer, iconX, iconY);

        // header: right of the item frame, vertically centered on it
        double headerLeft = iconX + PREVIEW_ICON_FRAME_BLOCKS / 2 + PREVIEW_PAD;
        TextDisplayLayerData header = previewText(3, List.of(content.title(), content.subtitle()),
                w / 2 - PREVIEW_PAD - headerLeft, PREVIEW_HEADER_HEIGHT);
        previewPlace(header, headerLeft + header.estimateContentWidthBlocks() / 2,
                iconY - header.estimateContentHeightBlocks() / 2);

        // body: under the header, top-aligned, left-aligned
        double bodyTop = h - 2 * PREVIEW_PAD - PREVIEW_ICON_FRAME_BLOCKS;
        TextDisplayLayerData body = previewText(1, content.body(),
                w - 2 * PREVIEW_PAD, bodyTop - PREVIEW_PAD);
        // without the texture the body text carries the panel colour instead
        // Built (and hidden) in the empty state too: a design adds layers, and updateButton needs the
        // same layer positions in both states.
        DisplayLayerData panel = Skin.enabled
                ? Skin.texture("display_entity_configuration", 0, 0, -0.03, w, h, 5, GUI_PATH, EVENT_PREVIEW_ID + "-skin") : null;
        body.setBackground(shown && panel == null ? PANEL_BACKGROUND : TRANSPARENT);
        previewPlace(body, -w / 2 + PREVIEW_PAD + body.estimateContentWidthBlocks() / 2,
                bodyTop - body.estimateContentHeightBlocks());

        DisplayLayerData frame = Skin.enabled
                ? Skin.texture("is_shape_icon", 0, 0, -0.01, PREVIEW_ICON_FRAME_BLOCKS, PREVIEW_ICON_FRAME_BLOCKS, 4, GUI_PATH, EVENT_PREVIEW_ID + "-frame") : null;
        frame = frame != null ? previewPlace(frame, iconX, iconY) : hiddenPreviewLayer(4, "-frame");
        panel = panel != null ? previewPlace(panel, 0, h / 2) : hiddenPreviewLayer(5, "-skin");
        if (!shown) {
            Skin.hide(frame);
            Skin.hide(panel);
        }
        return Skin.expand(new LayersData(List.of(body, itemLayer, header, frame, panel), GUI_PATH + ":" + EVENT_PREVIEW_ID, GUI_PATH));
    }

    /** Left-aligned preview text shrunk to fit {@code maxWidth x maxHeight} blocks (never wraps). */
    private static TextDisplayLayerData previewText(int position, List<String> lines, double maxWidth, double maxHeight) {
        TextDisplayLayerData text = new TextDisplayLayerData(0, 0, 0, GUI_PATH, EVENT_PREVIEW_ID + "-text" + position, position,
                TRANSPARENT, TextDisplay.TextAlignment.LEFT)
                .setText(lines);
        text.setAutoFitText(false);
        text.setTextShadow(true);
        double scale = Math.min(1, Math.min(maxWidth / Math.max(0.01, text.estimateContentWidthBlocks()),
                maxHeight / Math.max(0.01, text.estimateContentHeightBlocks())));
        float s = (float) Math.max(PREVIEW_MIN_TEXT_SCALE, scale);
        text.setScale(new Vector3f(s, s, 1f));
        return text;
    }

    /** Turns the layer with the panel and moves it to panel-local (x, yUp) - see {@link #eventPreviewDesign}. */
    private static <T extends DisplayLayerData> T previewPlace(T layer, double x, double yUp) {
        return placeTurned(layer, EVENT_PREVIEW_ROTATION_Y_DEGREES, x, yUp);
    }

    /** Turns {@code layer} by {@code rotationY} and moves it {@code x} right / {@code yUp} up along the
     * turned face - layers rotate around their own anchor, so offsets must be turned with them. */
    private static <T extends DisplayLayerData> T placeTurned(T layer, float rotationY, double x, double yUp) {
        layer.setRotationY(rotationY);
        layer.setTranslation(layer.getRotationQuaternion()
                .transform(new Vector3f(layer.getTranslation()).add((float) x, (float) yUp, 0f)));
        return layer;
    }

    private static ItemDisplayLayerData hiddenPreviewLayer(int position, String suffix) {
        return new ItemDisplayLayerData(0, 0, 0, GUI_PATH, EVENT_PREVIEW_ID + suffix, position)
                .setItemStack(new ItemStack(Material.AIR));
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
            List<String> body = new ArrayList<>();
            String mode = "?";
            if (session != null) {
                List<ShulkerSessionLog.SlotDiff> diffs = ShulkerSessionLog.diff(session.before(), session.after());
                mode = ShulkerSessionLog.MODE_HELD.equals(session.mode()) ? Lang.t("gui.shulker.held") : Lang.t("gui.shulker.placed");
                body.add(LABEL + Lang.t("gui.preview.where") + VALUE + (ev.world() != null ? ev.world() + " " + ev.x() + " " + ev.y() + " " + ev.z() : "?"));
                body.add("");
                body.add(LABEL + Lang.t("gui.shulker.added") + GOOD + Lang.t("gui.pieces", ShulkerSessionLog.totalAdded(diffs)));
                body.add(LABEL + Lang.t("gui.shulker.removed") + BAD + Lang.t("gui.pieces", ShulkerSessionLog.totalRemoved(diffs)));
            }
            body.add("");
            body.add(HINT + Lang.t("gui.preview.click-shulker"));
            applyEventPreview(player, new PreviewContent(rowIcon, VALUE + Lang.t("gui.shulker.title", ev.id()), LABEL + mode, body));
            return;
        }

        if (ev.unitUuid() == null) {
            applyEventPreview(player, new PreviewContent(new ItemStack(Material.BARRIER), VALUE + Lang.t("gui.event.title", ev.id()),
                    LABEL + Lang.t("gui.preview.no-item"), Lang.list("gui.preview.no-item-body").stream().map(s -> LABEL + s).toList()));
            return;
        }
        String unitUuid = ev.unitUuid();
        UnitPreviewData cached = unitPreviewCache.get(unitUuid);
        if (cached != null) {
            renderUnitPreview(player, unitUuid, cached, rowIcon);
            return;
        }
        applyEventPreview(player, new PreviewContent(rowIcon, VALUE + Lang.t("gui.loading"), MUTED + shortUuid(unitUuid), List.of(" ")));
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
        // split into sections (the item / its existence / where it is) so it reads at a glance
        List<String> body = new ArrayList<>();
        if (live != null) {
            body.add(LABEL + Lang.t("gui.item.amount") + VALUE + Lang.t("gui.pieces", live.getAmount()));
            body.add(LABEL + Lang.t("gui.item.enchants") + VALUE + formatEnchants(live));
        } else {
            body.add(MUTED + Lang.t("gui.item.not-loaded"));
        }
        body.add("");
        TrackedUnitRecord unit = data.unit();
        if (unit != null) {
            body.add(LABEL + Lang.t("gui.item.state") + (unit.alive() ? GOOD + Lang.t("gui.item.alive") : BAD + Lang.t("gui.item.destroyed") + " " + LABEL + "(" + unit.destroyedCause() + ")"));
            body.add(LABEL + Lang.t("gui.item.genesis") + VALUE + formatTime(unit.genesisAt()));
            body.add(LABEL + Lang.t("gui.item.origin") + VALUE + unit.origin());
        } else {
            body.add(LABEL + Lang.t("gui.item.state") + BAD + Lang.t("gui.item.not-in-db"));
        }
        body.add("");
        body.add(LABEL + Lang.t("gui.item.location") + VALUE + formatUnitLocation(data.location()));
        body.add(LABEL + Lang.t("gui.preview.events") + VALUE + data.eventCount());
        body.add("");
        body.add(HINT + Lang.t("gui.preview.click-detail"));
        applyEventPreview(player, new PreviewContent(live != null ? live : rowIcon, VALUE + itemTitle(live, data.material()),
                LABEL + (data.material() != null ? data.material() : "?") + "  " + MUTED + shortUuid(unitUuid), body));
    }

    /** Text colours for the preview (legacy codes, rendered by DisplayGUI's text layers). */
    private static final String LABEL = "§7", VALUE = "§f", GOOD = "§a", BAD = "§c", MUTED = "§8", HINT = "§e";

    /** First block of a UUID - the full one doesn't fit the panel; the detail page shows it whole. */
    private static String shortUuid(String uuid) {
        return uuid.length() > 8 ? uuid.substring(0, 8) + "..." : uuid;
    }

    private static String itemTitle(ItemStack live, String material) {
        if (live != null) {
            ItemMeta meta = live.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            }
            return live.getType().name();
        }
        return material != null ? material : Lang.t("gui.item.unknown");
    }

    private static String formatEnchants(ItemStack item) {
        Map<Enchantment, Integer> enchants = new HashMap<>(item.getEnchantments());
        if (item.getItemMeta() instanceof EnchantmentStorageMeta stored) {
            enchants.putAll(stored.getStoredEnchants());
        }
        if (enchants.isEmpty()) {
            return Lang.t("gui.none");
        }
        List<String> parts = new ArrayList<>();
        enchants.forEach((enchant, level) -> parts.add(enchant.getKey().getKey() + " " + level));
        return String.join(", ", parts);
    }

    private static String formatUnitLocation(UnitLocationRecord loc) {
        if (loc == null) {
            return Lang.t("gui.location.unknown");
        }
        String coords = loc.world() != null ? loc.world() + " " + loc.x() + " " + loc.y() + " " + loc.z() : "";
        String slot = loc.slot() != null ? " " + Lang.t("gui.location.slot", loc.slot()) : "";
        return switch (loc.locationType()) {
            case "PLAYER_INVENTORY" -> Lang.t("gui.location.inventory", resolvePlayerAlias(loc.playerUuid())) + slot;
            case "ENDER_CHEST" -> Lang.t("gui.location.ender-chest", resolvePlayerAlias(loc.playerUuid())) + slot;
            case "BLOCK_CONTAINER" -> (loc.containerType() != null ? loc.containerType() : Lang.t("gui.location.container")) + " " + coords + slot;
            case "GROUND" -> Lang.t("gui.location.ground", coords);
            case "PLACED_BLOCK" -> Lang.t("gui.location.placed-block", coords);
            case "PLACED_INTO_MENU" -> Lang.t("gui.location.menu", loc.menuName()) + slot;
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

    private void applyEventPreview(Player player, PreviewContent content) {
        Button preview = findOpenButton(player, EVENT_PREVIEW_ID);
        if (preview == null) {
            return; // events page no longer open
        }
        ButtonData data = ButtonData.builder()
                .layers(eventPreviewDesign(content))
                .id(EVENT_PREVIEW_ID)
                .build();
        preview.updateButton(data);
    }

    /** The live top-level button with this id in the player's currently open menu, or null. Used
     * to swap a button's content in place ({@link Button#updateButton(ButtonData)}) - DisplayGUI's
     * public API has no way to change a button after the menu has opened. */
    private static Button findOpenButton(Player player, String id) {
        ScreenPage page = PurrTechDisplayGUI.getPlugin().getDisplayManager().getPlayersMenu().get(player);
        if (page == null) {
            return null;
        }
        for (Button button : page.getButtons()) {
            if (id.equals(button.getData().getId())) {
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
            applyEventPreview(player, PreviewContent.HIDDEN);
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
            player.sendMessage(Lang.t("chat.event-no-item"));
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
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("chat.event-not-found", eventId)));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> openEventDetailPage(player, found.get()));
            } catch (SQLException e) {
                logger.severe("Admin GUI detail udalosti selhal: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Lang.t("common.lookup-failed")));
            }
        });
    }

    private void openEventDetailPage(Player player, EventRecord event) {
        // Overrides whatever openEventsPage registered - "když na to klikneš tak tě to tam portne,
        // ale pak když znovu otevřeš menu tak ti to otevře na samé stránce" is read here as "the
        // same page you teleported FROM", i.e. this detail page, not the list behind it.
        lastPage.put(player.getUniqueId(), () -> openEventDetail(player, event.id()));

        // "taky třeba ukládej informace kdo byl v okolí třeba 50 blocků" - captured at write-time by
        // NearbyPlayers.capture, shown here as its own scrollable list.
        List<String> nearbyLines = event.nearbyPlayers() != null
                ? List.of(event.nearbyPlayers().split(","))
                : List.of();

        List<String> infoLines = new ArrayList<>();
        infoLines.add(actionColour(event.eventType()) + categoryLabel(event.eventType())
                + MUTED + "  #" + event.id());
        infoLines.add(LABEL + Lang.t("gui.event.time") + VALUE + formatTime(event.timestamp()));
        infoLines.add(LABEL + Lang.t("gui.event.player") + VALUE + (event.playerUuid() != null ? resolvePlayerAlias(event.playerUuid()) : Lang.t("gui.unknown"))
                + (event.gamemode() != null ? MUTED + " (" + event.gamemode() + ")" : ""));
        infoLines.add(LABEL + Lang.t("gui.event.place") + VALUE + (event.world() != null
                ? event.world() + " " + event.x() + " " + event.y() + " " + event.z() : Lang.t("gui.unknown")));
        String detail = EventLineFormatter.formatDetail(event.detail());
        if (!detail.isEmpty()) {
            infoLines.add(LABEL + Lang.t("gui.event.detail") + VALUE + detail);
        }
        infoLines.add(LABEL + Lang.t("gui.event.nearby") + VALUE + nearbyLines.size() + LABEL + " " + Lang.t("gui.event.nearby-suffix"));

        boolean hasLocation = event.world() != null && event.x() != null && event.y() != null && event.z() != null;

        List<ButtonData> buttons = new ArrayList<>();
        // Shulker session: inventory-style grid of its contents on the right, so the usual info +
        // nearby column moves left to make room.
        ShulkerSessionLog.Session shulker = ShulkerSessionLog.EVENT_TYPE.equals(event.eventType())
                ? ShulkerSessionLog.parse(event.detail()) : null;
        double infoX = 0;
        if (shulker != null) {
            infoX = -2.4;
            infoLines.add(1, LABEL + Lang.t("gui.shulker.label") + VALUE + shulker.shulkerMaterial() + MUTED + " ("
                    + (ShulkerSessionLog.MODE_HELD.equals(shulker.mode()) ? Lang.t("gui.shulker.held") : Lang.t("gui.shulker.placed-short")) + ")");
            addShulkerGridButtons(buttons, shulker);
        }
        // info bottom / list bottom: the list is only TEXT_LIST_ROWS lines now, so both moved down
        buttons.add(infoCard("info", cx(infoX), cy(-0.8), 0.05, infoLines));
        buttons.add(scrollListButton("event-nearby", cx(infoX), cy(0.6), 0.05, nearbyLines,
                Lang.t("gui.event.nobody-nearby", (int) NearbyPlayers.DEFAULT_RADIUS_BLOCKS)));
        // Back + teleport in one row under the info column, well apart and in the same plane as the
        // rest: at x +1.0 the back button sat right next to a shulker event's change list, whose
        // (much deeper) hitbox took its clicks - there was no way out of the page ("nejde jít pryč").
        double navY = 1.4;
        buttons.add(navButton("back", cx(infoX - (hasLocation ? EVENT_NAV_SPREAD : 0)), cy(navY), 0.05,
                Lang.t("gui.event.back"), e -> openEventsPage(player)));
        if (hasLocation) {
            buttons.add(navButton("teleport", cx(infoX + EVENT_NAV_SPREAD), cy(navY), 0.05, Lang.t("gui.teleport"), e -> teleportToEvent(player, event)));
        }

        openScreen(player, buttons, "purrtechlog:event:" + event.id());
    }

    /** Event detail: how far (blocks) the back / teleport buttons sit left / right of the info column. */
    private static final double EVENT_NAV_SPREAD = 1.4;

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
                case ADDED -> Lang.t("gui.shulker.line-added", d.slot(), d.delta(), itemLabel(d.after()));
                case REMOVED -> Lang.t("gui.shulker.line-removed", d.slot(), -d.delta(), itemLabel(d.before()));
                case REPLACED -> Lang.t("gui.shulker.line-replaced", d.slot(), d.before().getAmount(), itemLabel(d.before()),
                        d.after().getAmount(), itemLabel(d.after()));
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
                Lang.t("gui.shulker.summary", added, removed),
                Lang.t("gui.shulker.legend"))));
        buttons.add(scrollListButton("shulker-changes", cx(SHULKER_GRID_CENTER_X), cy(belowGrid + 1.1), 0.05,
                changeLines, Lang.t("gui.shulker.unchanged")));
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
        String hoverText = Lang.t("gui.shulker.empty-slot");
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
                case ADDED -> " " + Lang.t("gui.shulker.hover-added", d.delta());
                case REMOVED -> " " + Lang.t("gui.shulker.hover-removed", -d.delta());
                case REPLACED -> " " + Lang.t("gui.shulker.hover-replaced", d.before().getAmount(), itemLabel(d.before()));
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
        return textBlock(id, x, y, z, text);
    }

    /** "když na to klikneš tak tě to tam portne" - teleports to the event's recorded block
     * coordinates (center of the block, at its Y - these are block coords, not exact sub-block
     * positions). No-ops with a chat message instead of throwing if the event has no location or
     * its world isn't currently loaded. */
    private void teleportToEvent(Player player, EventRecord event) {
        if (event.world() == null || event.x() == null || event.y() == null || event.z() == null) {
            player.sendMessage(Lang.t("chat.event-no-location"));
            return;
        }
        World world = Bukkit.getWorld(event.world());
        if (world == null) {
            player.sendMessage(Lang.t("chat.world-not-loaded", event.world()));
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
            return Lang.t("chat.date-future");
        }
        return Lang.t("chat.date-range");
    }

    /** Manual chat-entry fallback for a date the calendar can't easily reach (far past months) -
     * per "dej tam individuální tlačítko na zadání hodnoty do chatu". Keeps the calendar's
     * from/to session alive so {@link #applyManualDateInput} knows which bound to set. */
    private void beginManualDateInput(Player player) {
        awaitingSearchInput.put(player.getUniqueId(), SearchMode.EVENTS_DATE);
        player.sendMessage(Lang.t("chat.prompt-date", Lang.t("chat.cancel-word")));
    }

    private void applyManualDateInput(Player player, String raw) {
        CalendarState state = calendarSessions.get(player.getUniqueId());
        if (state == null) {
            player.sendMessage(Lang.t("chat.date-expired"));
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
                player.sendMessage(Lang.t("chat.date-invalid", raw));
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
                Lang.list("calendar.months").get(state.month.getMonthValue() - 1) + " " + state.month.getYear(), e -> {}));
        buttons.add(navButton("cal-next", cx(startX + 6 * colStep), cy(-1.3), 0.05, ">", e -> shiftCalendarMonth(player, 1)));

        double headerY = -0.95;
        List<String> weekdays = Lang.list("calendar.weekdays");
        for (int i = 0; i < weekdays.size(); i++) {
            buttons.add(navButton("cal-hdr-" + i, cx(startX + i * colStep), cy(headerY), 0.05,
                    weekdays.get(i), e -> {}));
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
        buttons.add(navButton("cal-cancel", cx(centerX - 0.9), cy(afterGridY), 0.05, Lang.t("gui.cancel"), e -> cancelCalendar(player)));
        buttons.add(navButton("cal-manual", cx(centerX + 0.9), cy(afterGridY), 0.05, Lang.t("gui.calendar.manual"), e -> beginManualDateInput(player)));

        String pageKey = "purrtechlog:calendar:" + (state.from ? "from" : "to") + ":" + state.month;
        openScreen(player, buttons, pageKey);
    }

    // === On-screen keyboard: menu-only free-text entry (UUID search for now; player search and
    // date-range entry are migrated off chat in later phases). One click = one character; state
    // lives in keyboardSessions, page is fully rebuilt on every click same as the rest of this file. ===

    private void openUuidSearchKeyboard(Player player) {
        keyboardSessions.put(player.getUniqueId(), new KeyboardSession(
                Lang.t("gui.main.search-uuid"), HEX_KEYS, 8, 32, true,
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
        infoLines.add(Lang.t("gui.keyboard.count", typed.length(), session.maxLength));
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
        buttons.add(navButton("kb-backspace", cx(-0.8), cy(controlY), 0.05, 24, 7, Lang.t("gui.keyboard.backspace"), e -> backspaceKeyboard(player)));
        buttons.add(navButton("kb-clear", cx(0.8), cy(controlY), 0.05, 24, 7, Lang.t("gui.keyboard.clear"), e -> clearKeyboard(player)));
        buttons.add(navButton("kb-cancel", cx(-0.8), cy(controlY + 0.5), 0.05, 24, 7, Lang.t("gui.cancel"), e -> cancelKeyboard(player)));
        buttons.add(navButton("kb-confirm", cx(0.8), cy(controlY + 0.5), 0.05, 24, 7, Lang.t("gui.keyboard.confirm"),
                e -> confirmKeyboard(player), canConfirm));
        // Manual chat-entry fallback, per "u zadávání UUID dej taky klidně tlačítko na zadání do
        // chatu" - same idea as the calendar's "Napsat rucne" button, so a full UUID doesn't need
        // 32 individual hex-key clicks.
        buttons.add(navButton("kb-manual", cx(0), cy(controlY + 1.0), 0.05, 60, 7, Lang.t("gui.keyboard.manual"),
                e -> beginManualKeyboardInput(player)));

        openScreen(player, buttons, "purrtechlog:keyboard:" + typed.length());
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
            player.sendMessage(Lang.t("chat.keyboard-incomplete", session.maxLength));
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
        String what = session.uuidFormat ? Lang.t("chat.keyboard-what-uuid")
                : Lang.t("chat.keyboard-what-value", session.maxLength, String.join("", session.keys));
        player.sendMessage(Lang.t("chat.keyboard-prompt", what, Lang.t("chat.cancel-word")));
    }

    private void applyManualKeyboardInput(Player player, String raw) {
        KeyboardSession session = keyboardSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Lang.t("chat.keyboard-expired"));
            return;
        }
        String normalized = session.uuidFormat ? raw.trim().replace("-", "").toLowerCase(Locale.ROOT) : raw.trim();
        if (normalized.length() != session.maxLength || !isFromAllowedKeys(normalized, session.keys)) {
            player.sendMessage(Lang.t("chat.keyboard-invalid", raw, session.maxLength, String.join("", session.keys)));
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
