package eu.purrtech.detaillogger.tracking;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Opt-in (off by default, {@code /purrlog debug on|off}) trace of everything the stack-handling
 * code does on a click/drag - what the event contained, which handler took it, and what every
 * touched stack looked like before and after - written to {@code plugins/<plugin>/debug.log} so
 * it can be sent along with a bug report. Costs nothing while off (one volatile read per call).
 * <p>
 * Stacks are printed as {@code TYPE x<amount> units=<n> [<first 8 chars of each UUID>]} -
 * enough to see whether a stack's unit count matches its amount and where each UUID went,
 * without dumping 64 full UUIDs per stack.
 */
public final class StackDebug {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile boolean enabled;
    private static File file;
    private static Logger logger;
    private static ItemTrackingService tracking;

    private StackDebug() {
    }

    public static void init(File dataFolder, Logger pluginLogger, ItemTrackingService itemTracking) {
        file = new File(dataFolder, "debug.log");
        logger = pluginLogger;
        tracking = itemTracking;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static File file() {
        return file;
    }

    public static void setEnabled(boolean on) {
        if (on) {
            enabled = true;
        }
        log("=== debug " + (on ? "ZAPNUTO" : "VYPNUTO") + " ===");
        enabled = on;
    }

    /** One line, timestamped. No-op while disabled. */
    public static void log(String message) {
        if (!enabled || file == null) {
            return;
        }
        String line = TIME.format(LocalTime.now()) + " " + message + System.lineSeparator();
        try {
            Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("debug.log zapis selhal: " + e);
            enabled = false;
        }
    }

    /** Compact one-line description of a stack, see the class doc. */
    public static String describe(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "-";
        }
        List<UUID> units = tracking != null ? tracking.readAllUnits(item) : List.of();
        StringBuilder sb = new StringBuilder(item.getType().name()).append(" x").append(item.getAmount());
        // What makes two stacks "different" (and so not mergeable): name, lore, enchants.
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName() && meta.displayName() != null) {
                sb.append(" name=\"").append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(meta.displayName())).append('"');
            }
            if (meta.hasLore()) {
                sb.append(" lore");
            }
            if (!item.getEnchantments().isEmpty()) {
                sb.append(" ench=").append(item.getEnchantments().size());
            }
        }
        if (units.isEmpty()) {
            return sb.append(" UNTRACKED").toString();
        }
        sb.append(" units=").append(units.size()).append(units.size() == item.getAmount() ? "" : " !MISMATCH").append(" [");
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (i >= 6) {
                sb.append("...");
                break;
            }
            sb.append(units.get(i).toString(), 0, 8);
        }
        String template = tracking.readTemplateKey(item);
        return sb.append("] tpl=").append(template).toString();
    }

    /** Every non-empty slot of an inventory, one per line, under a heading. */
    public static void dumpInventory(String heading, Inventory inventory) {
        if (!enabled || inventory == null) {
            return;
        }
        log(heading + " (" + inventory.getType() + ")");
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].getType().isAir()) {
                log("    slot " + slot + ": " + describe(contents[slot]));
            }
        }
    }

    public static void dumpView(String heading, InventoryView view) {
        if (!enabled) {
            return;
        }
        dumpInventory(heading + " TOP", view.getTopInventory());
        dumpInventory(heading + " BOTTOM", view.getBottomInventory());
    }
}
