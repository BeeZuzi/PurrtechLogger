package eu.purrtech.detaillogger.tracking.pdc;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Tracking PDC for arbitrary entities - every {@link Entity} is a {@code PersistentDataHolder} in
 * Bukkit, unlike plain blocks. Used narrowly to bridge a block's identity onto a
 * {@link org.bukkit.entity.FallingBlock} entity while it's mid-air, so landing can reconnect it
 * instead of it looking like destroy+genesis.
 */
public final class TrackedEntityTag {

    private final NamespacedKey uuidKey;
    private final NamespacedKey templateKey;

    public TrackedEntityTag(Plugin plugin) {
        this.uuidKey = new NamespacedKey(plugin, "track_uuid");
        this.templateKey = new NamespacedKey(plugin, "track_template");
    }

    public UUID readUuid(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String readTemplateKey(Entity entity) {
        return entity.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
    }

    public void write(Entity entity, UUID uuid, String templateKeyValue) {
        entity.getPersistentDataContainer().set(uuidKey, PersistentDataType.STRING, uuid.toString());
        entity.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, templateKeyValue);
    }

    public void strip(Entity entity) {
        entity.getPersistentDataContainer().remove(uuidKey);
        entity.getPersistentDataContainer().remove(templateKey);
    }
}
