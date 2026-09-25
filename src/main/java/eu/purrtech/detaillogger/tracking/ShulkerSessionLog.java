package eu.purrtech.detaillogger.tracking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encoding + diffing for {@code SHULKER_OPENED} events: one event per open/close session of a
 * shulker box, whose {@code detail} holds the full contents when it was opened and when it was
 * closed, so the admin GUI can redraw both in an inventory-style grid and color what the player
 * took out / put in.
 * <p>
 * Detail layout: flat readable summary fields first ({@code shulker}, {@code mode}, {@code added},
 * {@code removed} - what {@code EventLineFormatter#formatDetail} shows in text output), then
 * {@code before}/{@code after} arrays of {@code {"s":slot,"i":base64}} for non-empty slots only.
 * Items are stored as Paper's full item bytes (not just material + amount) so the GUI can show
 * the real item - enchant glint, custom name, custom model.
 */
public final class ShulkerSessionLog {

    public static final String EVENT_TYPE = "SHULKER_OPENED";
    public static final int SLOTS = 27;

    /** Placed shulker block vs. one opened while held in hand. */
    public static final String MODE_PLACED = "placed";
    public static final String MODE_HELD = "held";

    public enum Change { SAME, ADDED, REMOVED, REPLACED }

    /**
     * One slot's before/after. {@code delta} = after amount - before amount for the same item
     * (ADDED/REMOVED also cover a stack that only grew/shrank); for REPLACED it's 0.
     */
    public record SlotDiff(int slot, ItemStack before, ItemStack after, Change change, int delta) {
    }

    public record Session(String shulkerMaterial, String mode, ItemStack[] before, ItemStack[] after) {
    }

    private ShulkerSessionLog() {
    }

    public static String toJson(String shulkerMaterial, String mode, ItemStack[] before, ItemStack[] after) {
        List<SlotDiff> diffs = diff(before, after);
        JsonObject root = new JsonObject();
        root.addProperty("shulker", shulkerMaterial);
        root.addProperty("mode", mode);
        root.addProperty("added", String.valueOf(totalAdded(diffs)));
        root.addProperty("removed", String.valueOf(totalRemoved(diffs)));
        root.add("before", encodeSlots(before));
        root.add("after", encodeSlots(after));
        return root.toString();
    }

    /** Null if the detail isn't a (readable) shulker session. */
    public static Session parse(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(detailJson).getAsJsonObject();
            String material = root.has("shulker") ? root.get("shulker").getAsString() : null;
            String mode = root.has("mode") ? root.get("mode").getAsString() : null;
            return new Session(material, mode, decodeSlots(root.getAsJsonArray("before")),
                    decodeSlots(root.getAsJsonArray("after")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Just the shulker's material, without decoding any items - used for list row icons. */
    public static String shulkerMaterial(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(detailJson).getAsJsonObject();
            return root.has("shulker") ? root.get("shulker").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<SlotDiff> diff(ItemStack[] before, ItemStack[] after) {
        List<SlotDiff> result = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack b = at(before, slot);
            ItemStack a = at(after, slot);
            if (b == null && a == null) {
                result.add(new SlotDiff(slot, null, null, Change.SAME, 0));
            } else if (b == null) {
                result.add(new SlotDiff(slot, null, a, Change.ADDED, a.getAmount()));
            } else if (a == null) {
                result.add(new SlotDiff(slot, b, null, Change.REMOVED, -b.getAmount()));
            } else if (a.isSimilar(b)) {
                int delta = a.getAmount() - b.getAmount();
                Change change = delta > 0 ? Change.ADDED : delta < 0 ? Change.REMOVED : Change.SAME;
                result.add(new SlotDiff(slot, b, a, change, delta));
            } else {
                result.add(new SlotDiff(slot, b, a, Change.REPLACED, 0));
            }
        }
        return result;
    }

    /** Items put in: added/grown slots plus the new item of every replaced slot. */
    public static int totalAdded(List<SlotDiff> diffs) {
        int total = 0;
        for (SlotDiff d : diffs) {
            if (d.change() == Change.ADDED) {
                total += d.delta();
            } else if (d.change() == Change.REPLACED) {
                total += d.after().getAmount();
            }
        }
        return total;
    }

    /** Items taken out: removed/shrunk slots plus the old item of every replaced slot. */
    public static int totalRemoved(List<SlotDiff> diffs) {
        int total = 0;
        for (SlotDiff d : diffs) {
            if (d.change() == Change.REMOVED) {
                total += -d.delta();
            } else if (d.change() == Change.REPLACED) {
                total += d.before().getAmount();
            }
        }
        return total;
    }

    private static ItemStack at(ItemStack[] items, int slot) {
        if (items == null || slot >= items.length) {
            return null;
        }
        ItemStack item = items[slot];
        return item == null || item.getType() == Material.AIR ? null : item;
    }

    private static JsonArray encodeSlots(ItemStack[] items) {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack item = at(items, slot);
            if (item == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("s", slot);
            entry.addProperty("i", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            array.add(entry);
        }
        return array;
    }

    private static ItemStack[] decodeSlots(JsonArray array) {
        ItemStack[] items = new ItemStack[SLOTS];
        if (array == null) {
            return items;
        }
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            int slot = entry.get("s").getAsInt();
            if (slot < 0 || slot >= SLOTS) {
                continue;
            }
            try {
                items[slot] = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.get("i").getAsString()));
            } catch (RuntimeException e) {
                items[slot] = new ItemStack(Material.BARRIER); // unreadable (e.g. item removed in a later MC version)
            }
        }
        return items;
    }
}
