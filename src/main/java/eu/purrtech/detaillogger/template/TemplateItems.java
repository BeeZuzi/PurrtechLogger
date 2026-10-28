package eu.purrtech.detaillogger.template;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * The item behind a template: stored as an exact snapshot of the item the template was made from, and
 * rebuilt from it for {@code /purrlog give}. A template without a snapshot (written by hand, or made
 * before snapshots existed) can only be approximated from what it matches on.
 */
public final class TemplateItems {

    private TemplateItems() {
    }

    /**
     * @param item  one piece of the item to give
     * @param exact true if it is the stored original, false if it was only rebuilt from the template's
     *              material / custom-model-data / pdc-marker (name and lore patterns cannot be reversed)
     */
    public record Built(ItemStack item, boolean exact) {
    }

    /** Base64 of the full item (every component and plugin data), amount 1. Callers strip tracking tags first. */
    public static String encode(ItemStack item) {
        ItemStack one = item.clone();
        one.setAmount(1);
        return Base64.getEncoder().encodeToString(one.serializeAsBytes());
    }

    /** The stored item, or null if the snapshot cannot be read (hand-edited, damaged). */
    public static ItemStack decode(String snapshot) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(snapshot));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Built build(TemplateDefinition definition, Logger logger) {
        if (definition.itemSnapshot() != null) {
            ItemStack stored = decode(definition.itemSnapshot());
            if (stored != null) {
                return new Built(stored, true);
            }
            logger.warning("templates.yml: the stored item of '" + definition.key()
                    + "' cannot be read, rebuilding it from the template instead");
        }
        return new Built(approximate(definition), false);
    }

    private static ItemStack approximate(TemplateDefinition definition) {
        ItemStack item = new ItemStack(definition.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (definition.customModelData() != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of(definition.customModelData().floatValue()));
            meta.setCustomModelDataComponent(component);
        }
        if (definition.pdcMarkerKey() != null) {
            NamespacedKey key = NamespacedKey.fromString(definition.pdcMarkerKey());
            if (key != null) {
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, definition.pdcMarkerValue());
            }
        }
        item.setItemMeta(meta);
        return item;
    }
}
