package eu.purrtech.detaillogger.gui;

import eu.purrtech.detaillogger.db.dao.EventDao;
import eu.purrtech.displaygui.API.actions.MenuActionRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Registers the {@code [purrtechlog]} config action tag so a third-party DisplayGUI menu author
 * can opt a button into view-logging by adding {@code [purrtechlog] <template-key>} to that
 * button's {@code left-click}/{@code right-click} list in their own menu YAML, alongside its
 * {@code item:} field.
 * <p>
 * This is coarser than per-unit tracking: {@link eu.purrtech.displaygui.API.MenuButton} (the
 * handle available in the click context) has no back-reference to the config's {@code item:}
 * field or a specific tracked UUID - DisplayGUI buttons are typically static UI declarations, not
 * built per physical item instance the way this plugin's own admin GUI is. So this logs a
 * template-level {@code VIEWED_IN_MENU} event ({@code unit_uuid} left null, matching the schema's
 * existing "anomalous/unmatched" allowance) rather than pretending to know which specific unit was
 * looked at.
 */
public final class MenuViewLoggingAction {

    private MenuViewLoggingAction() {
    }

    public static void register(EventDao eventDao) {
        MenuActionRegistry.register("purrtechlog", rawArgs -> context -> {
            String templateKey = rawArgs == null ? null : rawArgs.trim();
            if (templateKey == null || templateKey.isEmpty()) {
                return;
            }
            Player player = context.player();
            long now = System.currentTimeMillis();
            Location loc = player.getLocation();
            String detail = "{\"template_key\":\"" + escape(templateKey) + "\"}";
            eventDao.enqueue(null, "VIEWED_IN_MENU", now, loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), player.getUniqueId().toString(), detail);
        });
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
