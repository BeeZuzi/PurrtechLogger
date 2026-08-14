package eu.purrtech.detaillogger.tracking.pdc;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single place that reads/writes tracking PDC on an {@link ItemStack}. Two mutually-exclusive
 * representations, chosen by unit count: a scalar {@code track_uuid} for a single physical unit
 * ({@code amount == 1}), or a comma-joined {@code track_uuid_list} for a merged stack of several
 * units ({@code amount == list.size()}). A stack must never carry both keys at once -
 * {@link #writeUnits} is the only place that decides which representation to use, so every writer
 * goes through it.
 */
public final class TrackedItemTag {

    private final NamespacedKey uuidKey;
    private final NamespacedKey uuidListKey;
    private final NamespacedKey templateKey;

    public TrackedItemTag(Plugin plugin) {
        this.uuidKey = new NamespacedKey(plugin, "track_uuid");
        this.uuidListKey = new NamespacedKey(plugin, "track_uuid_list");
        this.templateKey = new NamespacedKey(plugin, "track_template");
    }

    public boolean isTracked(ItemStack item) {
        return !readUnits(item).isEmpty();
    }

    /**
     * Scalar-only read - null unless the item carries a single-unit {@code track_uuid} tag. Use
     * {@link #readUnits} to also cover merged stacks.
     */
    public UUID readUuid(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
        return parseUuid(raw);
    }

    /**
     * Every tracked unit currently represented by this stack, regardless of whether it's tagged
     * as a single scalar UUID or a merged list. Empty (never null) if untracked. The returned
     * list's size always matches whichever representation is present - callers doing a
     * reconciliation pass compare this against {@link ItemStack#getAmount()}.
     */
    public List<UUID> readUnits(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return List.of();
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String rawList = pdc.get(uuidListKey, PersistentDataType.STRING);
        if (rawList != null) {
            List<UUID> units = new ArrayList<>();
            for (String piece : rawList.split(",")) {
                UUID uuid = parseUuid(piece);
                if (uuid != null) {
                    units.add(uuid);
                }
            }
            return units;
        }

        UUID scalar = parseUuid(pdc.get(uuidKey, PersistentDataType.STRING));
        return scalar == null ? List.of() : List.of(scalar);
    }

    public String readTemplateKey(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
    }

    /**
     * Writes the given units as whichever representation fits (scalar for one, list for several),
     * clearing the other key so a stack never carries both. Does not touch
     * {@link ItemStack#setAmount}, since callers already know the intended amount and some (e.g.
     * a reconciliation pass fixing up a Bukkit-cloned split) must not have it changed as a
     * side effect.
     */
    public void writeUnits(ItemStack item, List<UUID> uuids, String templateKeyValue) {
        if (uuids.isEmpty()) {
            strip(item);
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("Item has no ItemMeta: " + item.getType());
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (uuids.size() == 1) {
            pdc.set(uuidKey, PersistentDataType.STRING, uuids.get(0).toString());
            pdc.remove(uuidListKey);
        } else {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < uuids.size(); i++) {
                if (i > 0) {
                    joined.append(',');
                }
                joined.append(uuids.get(i));
            }
            pdc.set(uuidListKey, PersistentDataType.STRING, joined.toString());
            pdc.remove(uuidKey);
        }
        pdc.set(templateKey, PersistentDataType.STRING, templateKeyValue);
        item.setItemMeta(meta);
    }

    public void writeSingleUnit(ItemStack item, UUID uuid, String templateKeyValue) {
        writeUnits(item, List.of(uuid), templateKeyValue);
    }

    public void strip(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(uuidKey);
        pdc.remove(uuidListKey);
        pdc.remove(templateKey);
        item.setItemMeta(meta);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
