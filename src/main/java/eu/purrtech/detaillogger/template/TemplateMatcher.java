package eu.purrtech.detaillogger.template;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;

/**
 * Matches items/blocks against the currently loaded {@link TemplateRegistry}.
 */
public final class TemplateMatcher {

    private final TemplateRegistry registry;

    public TemplateMatcher(TemplateRegistry registry) {
        this.registry = registry;
    }

    public Optional<TemplateDefinition> match(ItemStack item) {
        for (TemplateDefinition def : registry.definitions()) {
            if (def.trackItems() && matchesItem(item, def)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    public Optional<TemplateDefinition> match(Block block) {
        for (TemplateDefinition def : registry.definitions()) {
            if (def.trackBlocks() && def.material() == block.getType()) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    private boolean matchesItem(ItemStack item, TemplateDefinition def) {
        if (item.getType() != def.material()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();

        if (def.customModelData() != null && !hasCustomModelData(meta, def.customModelData())) {
            return false;
        }

        if (def.pdcMarkerKey() != null && !hasPdcMarker(meta, def.pdcMarkerKey(), def.pdcMarkerValue())) {
            return false;
        }

        if (def.namePattern() != null && !matchesName(meta, def.namePattern())) {
            return false;
        }

        return def.lorePattern() == null || matchesLore(meta, def.lorePattern());
    }

    private boolean hasCustomModelData(ItemMeta meta, int expected) {
        // 1.21.5+: the old scalar getCustomModelData() is deprecated in favour of the
        // component-based API. An integer CMD set via the legacy API is equivalent to a
        // single-element float list here.
        if (meta == null || !meta.hasCustomModelDataComponent()) {
            return false;
        }
        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        return floats.size() == 1 && floats.get(0) == (float) expected;
    }

    private boolean hasPdcMarker(ItemMeta meta, String markerKey, String markerValue) {
        if (meta == null) {
            return false;
        }
        NamespacedKey key = NamespacedKey.fromString(markerKey);
        if (key == null) {
            return false;
        }
        String value = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return markerValue.equals(value);
    }

    private boolean matchesName(ItemMeta meta, java.util.regex.Pattern pattern) {
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }
        Component name = meta.displayName();
        if (name == null) {
            return false;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        return pattern.matcher(plain).find();
    }

    private boolean matchesLore(ItemMeta meta, java.util.regex.Pattern pattern) {
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        List<Component> lore = meta.lore();
        if (lore == null) {
            return false;
        }
        return lore.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .anyMatch(line -> pattern.matcher(line).find());
    }
}
