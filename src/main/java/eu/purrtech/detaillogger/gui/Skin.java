package eu.purrtech.detaillogger.gui;

import eu.purrtech.displaygui.API.DesignFit;
import eu.purrtech.displaygui.API.DesignRegistry;
import eu.purrtech.displaygui.API.data.buttonData.ButtonData;
import eu.purrtech.displaygui.API.data.buttonData.ButtonListScrollButtonData;
import eu.purrtech.displaygui.API.data.layerData.BlockDisplayLayerData;
import eu.purrtech.displaygui.API.data.layerData.DisplayLayerData;
import eu.purrtech.displaygui.API.data.layerData.ItemDisplayLayerData;
import eu.purrtech.displaygui.API.data.layerData.LayersData;
import eu.purrtech.displaygui.API.data.layerData.TextDisplayLayerData;
import eu.purrtech.displaygui.API.data.screenPageData.ScreenPageData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Texturova varianta menu (config {@code textured-gui: true}): pred otevrenim nahradi barevna pozadi textovych
 * vrstev ItemsAdder texturami z namespace {@code king} (D:\Minecraft\pluginy\PurrTechDisplayGUI\run\plugins\ItemsAdder).
 * - nejvetsi barevna vrstva stranky -> panel roztazeny na jeji width/height (vycentrovany na vrstvu)
 * - barevny popisek tlacitka -> ramecek za textem (textura podle barvy), text dostane pruhledne pozadi
 * - klikaci tlacitko bez popisku (item slot, ikona) -> slot/ramecek za celym tlacitkem, puvodni podklad se schova
 * - neklikaci barevne vrstvy s prazdnym textem (policka hry, dlazdice) zustanou jak jsou
 * Velikost/posun textury se pocita z viditelneho obsahu PNG (bbox na platne), ne z celeho platna.
 * Bez ItemsAdderu (nebo chybejici textury) se menu necha klasicke.
 * Misto vestavenych textur muze config urcit vlastni design z DisplayGUI (button-/panel-/slot-design): roztahne se
 * pres {@link DesignFit} tak, aby jeho rodicovska (nejnizsi) vrstva pokryla tlacitko/panel.
 */
public final class Skin {

    public static boolean enabled;
    /** Nazvy designu z DisplayGUI (designs/*.yml) misto vestavenych textur; null = vestavena textura. */
    public static String buttonDesign, panelDesign, slotDesign;

    /**
     * Nacte {@code path} z configu: bud {@code true/false}, nebo sekce {@code enabled} + volitelne
     * {@code button-design}, {@code panel-design}, {@code slot-design}.
     */
    public static void configure(ConfigurationSection cfg, String path, boolean def) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        enabled = s != null ? s.getBoolean("enabled", def) : cfg.getBoolean(path, def);
        buttonDesign = s == null ? null : blankToNull(s.getString("button-design"));
        panelDesign = s == null ? null : blankToNull(s.getString("panel-design"));
        slotDesign = s == null ? null : blankToNull(s.getString("slot-design"));
        CACHE.clear();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Neviditelna kotva za design: vrstvy designu se k ni doplni v {@link #expand} (az po nastaveni posunu/rotace). */
    private static final class Anchor extends ItemDisplayLayerData {
        final LayersData design;
        final double w, h;
        boolean hidden;

        Anchor(LayersData design, double x, double y, double deep, double w, double h, int pos, String path, String id) {
            super(x, y, deep, path, id, pos);
            this.design = design;
            this.w = w;
            this.h = h;
            setItemStack(new ItemStack(Material.AIR));
            setWidth(0f);
            setHeight(0f);
        }
    }

    /** Ramecek tlacitka: okraj kolem textu (bloky). */
    private static final double FRAME_PAD_X = 0.14;
    private static final double FRAME_PAD_Y = 0.08;
    private static final double BEHIND = 0.01;
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);
    private static final Color NEUTRAL = Color.fromARGB(255, 60, 60, 60);

    /** Viditelny obsah textury na platne: canvas, minX, minY, maxX, maxY (px, alfa > 10). */
    private static final Map<String, int[]> BBOX = Map.ofEntries(
            Map.entry("display_entity_configuration", new int[]{1080, 100, 216, 980, 864}),
            Map.entry("player_stats", new int[]{1080, 224, 196, 852, 884}),
            Map.entry("menu_preview", new int[]{1080, 160, 160, 920, 920}),
            Map.entry("icon_selection", new int[]{1080, 192, 100, 888, 980}),
            Map.entry("custom_menu_editor", new int[]{1080, 256, 108, 824, 968}),
            Map.entry("layout_presets", new int[]{1080, 96, 364, 980, 716}),
            Map.entry("save_menu", new int[]{1080, 176, 456, 904, 624}),
            Map.entry("cme_button", new int[]{1080, 164, 448, 916, 632}),
            Map.entry("dec_entity_type_add", new int[]{1080, 160, 380, 920, 696}),
            Map.entry("is_page", new int[]{1080, 212, 472, 864, 608}),
            Map.entry("is_shape_icon", new int[]{1080, 304, 308, 772, 768}));

    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private Skin() {
    }

    /** Vrati stranku s texturami (meni ji na miste), nebo beze zmeny kdyz je varianta vypnuta. Panel = width/height pozadi. */
    public static ScreenPageData apply(ScreenPageData page, String panel) {
        return apply(page, panel, -1, -1);
    }

    /** Jako {@link #apply(ScreenPageData, String)}, ale panel ma danou viditelnou velikost (bloky). */
    public static ScreenPageData apply(ScreenPageData page, String panel, double width, double height) {
        if (!enabled) return page;
        skinPanel(page.getPageData().getLayersData(), panel, width, height);
        for (ButtonData b : page.getButtonsData()) skinButton(b);
        expandAll(page);
        return page;
    }

    /** Jen panel stranky s danou viditelnou velikosti (bloky) - pro stranky, jejichz pozadi nema width/height (kolo stesti). */
    public static ScreenPageData applyPanel(ScreenPageData page, String panel, double width, double height) {
        if (enabled) {
            skinPanel(page.getPageData().getLayersData(), panel, width, height);
            expandAll(page);
        }
        return page;
    }

    private static void expandAll(ScreenPageData page) {
        expand(page.getPageData().getLayersData());
        page.getButtonsData().forEach(Skin::expandButton);
    }

    private static void expandButton(ButtonData b) {
        if (b.getLayersData() != null) expand(b.getLayersData());
        if (b instanceof ButtonListScrollButtonData list) list.getItems().forEach(Skin::expandButton);
    }

    /**
     * Doplni vrstvy designu ke kazde kotve (opakovane volani = stejne pozice, jen prepocet). Volat po tom, co je
     * kotva na svem miste (posun, rotace) - {@link #apply} to dela sam, rucne jen u vrstev mimo nej (updateButton).
     */
    public static LayersData expand(LayersData layers) {
        Map<Integer, DisplayLayerData> map = layers.getDisplayLayerData();
        for (DisplayLayerData l : List.copyOf(map.values())) {
            if (!(l instanceof Anchor a)) continue;
            int[] next = {map.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1};
            List<DisplayLayerData> parts = DesignFit.fit(a.design, a.getX(), a.getY(), a.getDeep(), a.w, a.h,
                    k -> map.entrySet().stream().filter(e -> e.getValue().getId().equals(a.getId() + "#" + k))
                            .map(Map.Entry::getKey).findFirst().orElseGet(() -> next[0]++),
                    a.getPath(), a.getId());
            Quaternionf r = a.getRotationQuaternion();
            for (DisplayLayerData p : parts) {
                p.setTranslation(new Vector3f(a.getTranslation()).add(r.transform(new Vector3f(p.getTranslation()))));
                p.setRotationX(p.getRotationX() + a.getRotationX());
                p.setRotationY(p.getRotationY() + a.getRotationY());
                p.setRotationZ(p.getRotationZ() + a.getRotationZ());
                if (a.hidden) p.setScale(new Vector3f());
                map.put(p.getPosition(), p);
            }
        }
        return layers;
    }

    /** Schova texturu/design vrstvy {@code l} (vestavena -> AIR, design -> nulova velikost po expand). */
    public static void hide(DisplayLayerData l) {
        if (l instanceof Anchor a) a.hidden = true;
        else if (l instanceof ItemDisplayLayerData i) i.setItemStack(new ItemStack(Material.AIR));
    }

    public static void skinButton(ButtonData b) {
        if (!enabled || b.getLayersData() == null) return;
        // uz otexturovane (znovu otevrena stejna data) nebo si texturu kresli samo (vrstva "-skin")
        if (b.getLayersData().getDisplayLayerData().values().stream().anyMatch(l -> l.getId().endsWith("-skin"))) return;
        if (b instanceof ButtonListScrollButtonData list) {
            list.getItems().forEach(Skin::skinButton);
            // kazdy radek ma vlastni ramecek -> spolecne tmave pozadi seznamu pryc
            for (DisplayLayerData l : b.getLayersData().getDisplayLayerData().values()) {
                if (l instanceof TextDisplayLayerData t && colored(t) && t.getText().stream().allMatch(String::isBlank)) t.setScale(new Vector3f());
            }
            return;
        }
        boolean framed = false;
        for (DisplayLayerData l : Map.copyOf(b.getLayersData().getDisplayLayerData()).values()) {
            if (l instanceof TextDisplayLayerData t && colored(t) && !t.getText().stream().allMatch(String::isBlank)) {
                frame(b.getLayersData(), t, t.getBackground());
                framed = true;
            }
        }
        if (framed) {
            // barevne plochy bez textu (okraje kolem popisku) by koukaly zpoza ramecku
            for (DisplayLayerData l : b.getLayersData().getDisplayLayerData().values()) {
                if (l instanceof TextDisplayLayerData t && colored(t) && t.getText().stream().allMatch(String::isBlank)) t.setScale(new Vector3f());
            }
        } else if (b.getLeftClickEvent() != null || b.getRightClickEvent() != null) {
            slot(b);
        }
    }

    /**
     * Klikaci tlacitko bez barevneho popisku (item slot, ikona, barevna plocha): textura za celym tlacitkem -
     * ctvercove = slot ({@code is_shape_icon}), jinak ramecek podle barvy. Podklad (BlockDisplay / barevna vrstva bez textu)
     * se schova nulovou velikosti, takze ho pozdejsi setBlock/setBackground za behu nevrati.
     * Bez podkladu ma textura velikost hitboxu a je za prvni vrstvou tlacitka.
     */
    private static void slot(ButtonData b) {
        LayersData layers = b.getLayersData();
        if (layers.getDisplayLayerData().isEmpty()) return;
        DisplayLayerData ref = null;
        Vector3f center = null;
        double w = b.getWidth(), h = b.getHeight();
        Color color = NEUTRAL;
        for (DisplayLayerData l : layers.getDisplayLayerData().values()) {
            if (l instanceof BlockDisplayLayerData) {
                Vector3f s = l.getScale(); // blok roste od pocatku do +x/+y/+z -> stred, textura na predni stene
                ref = l;
                w = s.x;
                h = s.y;
                center = new Vector3f(s.x / 2, s.y / 2, s.z);
                break;
            }
            if (l instanceof TextDisplayLayerData t && colored(t)) {
                Vector3f s = t.getFittedScale(); // text roste nahoru, vodorovne vycentrovany
                ref = t;
                w = t.backgroundWidthBlocksAtUnitScale() * s.x;
                h = t.backgroundHeightBlocksAtUnitScale() * s.y;
                center = new Vector3f(0, (float) h / 2, 0);
                color = t.getBackground();
                break;
            }
        }
        boolean hide = ref != null;
        if (ref == null) {
            ref = layers.getDisplayLayerData().get(layers.getDisplayLayerData().keySet().stream().min(Integer::compare).orElseThrow());
            center = new Vector3f(0, ref instanceof TextDisplayLayerData ? (float) h / 2 : 0, (float) -BEHIND);
        }
        String tex = w / h > 0.6 && w / h < 1.6 ? "is_shape_icon" : frameFor(color);
        int pos = layers.getDisplayLayerData().keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        ItemDisplayLayerData f = texture(tex, ref.getX(), ref.getY(), ref.getDeep(), w, h, pos, ref.getPath(), ref.getId() + "-skin");
        if (f == null) return;
        Quaternionf r = ref.getRotationQuaternion();
        f.setTranslation(new Vector3f(ref.getTranslation()).add(r.transform(center.add(f.getTranslation()))));
        f.setRotationX(ref.getRotationX());
        f.setRotationY(ref.getRotationY());
        f.setRotationZ(ref.getRotationZ());
        layers.getDisplayLayerData().put(pos, f);
        expand(layers);
        if (hide) ref.setScale(new Vector3f());
    }

    /**
     * Za behu zmenene pozadi popisku: u otexturovaneho popisku postavi ramecek znovu (nova barva i delka textu),
     * jinak jen nastavi pozadi jako driv. Volat misto {@code t.setBackground(bg)} pred {@code updateButton()}.
     */
    public static void recolor(ButtonData b, TextDisplayLayerData t, Color bg) {
        if (enabled && b.getLayersData().getDisplayLayerData().values().stream().anyMatch(l -> l.getId().equals(t.getId() + "-skin"))) {
            frame(b.getLayersData(), t, bg);
        } else {
            t.setBackground(bg);
        }
    }

    /** Ramecek za textem {@code t} podle barvy {@code bg} (pruhledna = bez ramecku); text dostane pruhledne pozadi. */
    private static void frame(LayersData layers, TextDisplayLayerData t, Color bg) {
        Integer old = layers.getDisplayLayerData().entrySet().stream()
                .filter(e -> e.getValue().getId().equals(t.getId() + "-skin")).map(Map.Entry::getKey).findFirst().orElse(null);
        Vector3f s = t.getFittedScale();
        double w = t.backgroundWidthBlocksAtUnitScale() * s.x + FRAME_PAD_X;
        double h = t.backgroundHeightBlocksAtUnitScale() * s.y + FRAME_PAD_Y;
        ItemDisplayLayerData f = layer(t, bg.getAlpha() == 0 ? null : frameFor(bg), w, h, layers, old);
        if (f == null) {
            if (old == null) t.setBackground(bg); // textura neni -> klasicke pozadi
            return;
        }
        // text roste od sve pozice nahoru, polozka je vycentrovana -> posun o pul vysky textu (v rotovanem prostoru vrstvy)
        Vector3f up = t.getRotationQuaternion().transform(new Vector3f(0, (float) (h - FRAME_PAD_Y) / 2f, 0));
        f.setTranslation(new Vector3f(t.getTranslation()).add(up).add(f.getTranslation()));
        expand(layers);
        t.setBackground(CLEAR);
    }

    /** {@code width < 0} = velikost z width/height nejvetsi barevne vrstvy. */
    private static void skinPanel(LayersData layers, String panel, double width, double height) {
        TextDisplayLayerData biggest = null;
        for (DisplayLayerData l : layers.getDisplayLayerData().values()) {
            if (l instanceof TextDisplayLayerData t && colored(t)
                    && (biggest == null || t.getWidth() * t.getHeight() > biggest.getWidth() * biggest.getHeight())) biggest = t;
        }
        if (biggest == null) return;
        double w = width < 0 ? biggest.getWidth() : width, h = width < 0 ? biggest.getHeight() : height;
        if (layer(biggest, panel, w, h, layers, null) == null) return;
        expand(layers);
        biggest.setBackground(CLEAR); // panel lezi za ni - i s textem (nadpis) by ho jinak prekryla
        // ostatni barevne vrstvy pozadi (okraje, vyplne) by panel prekryly
        for (DisplayLayerData l : layers.getDisplayLayerData().values()) {
            if (l instanceof TextDisplayLayerData t && colored(t) && t.getText().stream().allMatch(String::isBlank)) t.setBackground(CLEAR);
        }
    }

    /**
     * Polozka s texturou za vrstvou {@code over}, viditelny obsah {@code w x h} bloku vycentrovany na jeji pozici.
     * {@code reuse} = klic existujici vrstvy, kterou nahradi (stejna pozice = stejna entita pri updateButton); tex null = skryt.
     */
    private static ItemDisplayLayerData layer(DisplayLayerData over, String tex, double w, double h, LayersData layers, Integer reuse) {
        int pos = reuse != null ? reuse : layers.getDisplayLayerData().keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        ItemDisplayLayerData f = tex == null ? null : texture(tex, over.getX(), over.getY(), over.getDeep() - BEHIND, w, h, pos, over.getPath(), over.getId() + "-skin");
        if (f == null) {
            if (reuse != null) {
                hide(layers.getDisplayLayerData().get(reuse));
                expand(layers);
            }
            return null;
        }
        f.setTranslation(over.getRotationQuaternion().transform(f.getTranslation()));
        f.setRotationX(over.getRotationX());
        f.setRotationY(over.getRotationY());
        f.setRotationZ(over.getRotationZ());
        layers.getDisplayLayerData().put(pos, f);
        return f;
    }

    /** Ramecek tlacitka podle barvy, viditelny obsah {@code w x h} bloku vycentrovany na (x, y); null = textura neni. */
    public static ItemDisplayLayerData frameTexture(Color c, double x, double y, double deep, double w, double h, int pos) {
        return enabled ? texture(frameFor(c), x, y, deep, w, h, pos, "", "skin") : null;
    }

    /**
     * Vrstva s texturou {@code king:<tex>}: viditelny obsah {@code w x h} bloku vycentrovany na (x, y); null = textura neni.
     * Je-li pro tento druh (ramecek / panel / slot) v configu design, vrati misto ni kotvu designu (viz {@link #expand}).
     */
    static ItemDisplayLayerData texture(String tex, double x, double y, double deep, double w, double h, int pos, String path, String id) {
        LayersData design = design(tex);
        if (design != null) return new Anchor(design, x, y, deep, w, h, pos, path, id);
        ItemStack item = item(tex);
        int[] bb = BBOX.get(tex);
        if (item == null || bb == null) return null;
        double canvas = bb[0];
        double layerW = w * canvas / (bb[3] - bb[1]);
        double layerH = h * canvas / (bb[4] - bb[2]);
        // posun stredu platna tak, aby stred viditelneho obsahu sedel na pozici (model: +x doprava, +y nahoru)
        double dx = -((bb[1] + bb[3]) / 2.0 - canvas / 2) / canvas * layerW;
        double dy = ((bb[2] + bb[4]) / 2.0 - canvas / 2) / canvas * layerH;

        ItemDisplayLayerData f = new ItemDisplayLayerData(x, y, deep, path, id, pos);
        f.setItemStack(item);
        f.setScale(new Vector3f((float) layerW, (float) layerH, 1f));
        f.setTranslation(new Vector3f((float) dx, (float) dy, 0));
        f.setBrightness(new Display.Brightness(15, 15));
        f.setWidth(0f); // nikdy "nejvetsi vrstva" - velikost obrazovky i pivot hitboxu urcuje dal puvodni vrstva
        f.setHeight(0f);
        return f;
    }

    private static final Set<String> FRAMES = Set.of("save_menu", "is_page", "dec_entity_type_add", "cme_button");
    private static final Set<String> WARNED = new HashSet<>();

    /** Design z configu pro druh textury {@code tex}; null = zadny / neexistuje (jednou varuje). */
    private static LayersData design(String tex) {
        String name = tex.equals("is_shape_icon") ? slotDesign : FRAMES.contains(tex) ? buttonDesign : panelDesign;
        if (name == null) return null;
        LayersData design = DesignRegistry.get(name);
        if (design == null && WARNED.add(name)) {
            Bukkit.getLogger().warning("[PurrTech] textured-gui: design '" + name + "' v DisplayGUI neexistuje, pouzivam vestavene textury.");
        }
        return design;
    }

    private static boolean colored(TextDisplayLayerData t) {
        return t.getBackground() != null && t.getBackground().getAlpha() > 0;
    }

    /** Barva puvodniho pozadi -> ramecek: zelena = potvrdit/hrat, cervena = zrusit, modra, jinak neutralni. */
    static String frameFor(Color c) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue();
        if (g > r + 15 && g > b + 15) return "save_menu";
        if (r > g + 15 && r > b + 15) return "is_page";
        if (b > r + 15 && b > g + 15) return "dec_entity_type_add";
        return "cme_button";
    }

    /** ItemsAdder polozka {@code king:<tex>} pres API (reflexe, zadna zavislost); null = neni. */
    private static ItemStack item(String tex) {
        return CACHE.computeIfAbsent(tex, k -> {
            try {
                // Paper izoluje classloadery pluginu -> tridu bereme z classloaderu samotneho ItemsAdderu
                org.bukkit.plugin.Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
                if (ia == null) return null;
                Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack", true, ia.getClass().getClassLoader());
                Object stack = cs.getMethod("getInstance", String.class).invoke(null, "king:" + k);
                return stack == null ? null : (ItemStack) cs.getMethod("getItemStack").invoke(stack);
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        });
    }
}
