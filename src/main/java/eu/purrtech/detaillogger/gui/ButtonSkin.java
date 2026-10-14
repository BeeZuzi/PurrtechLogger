package eu.purrtech.detaillogger.gui;

import eu.purrtech.displaygui.API.data.layerData.ItemDisplayLayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Vector3f;

import java.lang.reflect.Method;

/**
 * Optional ItemsAdder texture behind every text button (config {@code gui.textures}). The texture is a
 * flat 1080x1080 canvas with the visible frame at x 242..838, y 476..602 (centred), so the layer is
 * stretched by canvas/visible to make the FRAME - not the canvas - match the button size. Without
 * ItemsAdder (or with the item missing) {@link #frame} returns null and buttons keep the plain
 * coloured-text look. Resolved through reflection so ItemsAdder stays a soft dependency.
 */
final class ButtonSkin {

    private static final double CANVAS = 1080, FRAME_W = 596, FRAME_H = 126;
    /** Frame height in blocks - a one-line label plus a little air. */
    private static final double FRAME_HEIGHT_BLOCKS = 0.4;
    private static final double FRAME_PADDING_BLOCKS = 0.15;

    private static Plugin plugin;
    private static ItemStack cached;

    private ButtonSkin() {}

    static void init(Plugin p) {
        plugin = p;
        cached = null;
    }

    /** Layer for a button whose hitbox is {@code widthPixels} wide, or null when textures are off/unavailable. */
    static ItemDisplayLayerData frame(String path, String id, double widthPixels, float rotationY) {
        ItemStack item = item();
        if (item == null) return null;
        double visW = widthPixels / 16.0 + FRAME_PADDING_BLOCKS;
        ItemDisplayLayerData layer = new ItemDisplayLayerData(0, 0, -0.02, path, id + "-frame", 2).setItemStack(item);
        float sx = (float) (visW * CANVAS / FRAME_W), sy = (float) (FRAME_HEIGHT_BLOCKS * CANVAS / FRAME_H);
        layer.setScale(new Vector3f(sx, sy, sx));
        if (rotationY != 0f) layer.setRotationY(rotationY);
        return layer;
    }

    private static ItemStack item() {
        if (plugin == null) return null;
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("gui.textures", true)) return null;
        if (cached != null) return cached.clone();
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null;
        try {
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = cs.getMethod("getInstance", String.class)
                    .invoke(null, cfg.getString("gui.button-texture", "king:mp_button_click_me"));
            if (stack == null) return null; // IA data not loaded yet - retry on the next button
            Method get = cs.getMethod("getItemStack");
            cached = (ItemStack) get.invoke(stack);
            return cached == null ? null : cached.clone();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
