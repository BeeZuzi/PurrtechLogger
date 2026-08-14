package eu.purrtech.detaillogger.tracking.pdc;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Tracking PDC for blocks. Only {@link TileState} blocks (chest, barrel, furnace, shulker box...)
 * support {@code PersistentDataHolder} - plain blocks (stone, ore...) are not holders at all, so
 * their identity lives purely in the DB keyed by world/coordinates (see the block-tracking phase).
 */
public final class TrackedBlockTag {

    private final NamespacedKey uuidKey;
    private final NamespacedKey templateKey;

    public TrackedBlockTag(Plugin plugin) {
        this.uuidKey = new NamespacedKey(plugin, "track_uuid");
        this.templateKey = new NamespacedKey(plugin, "track_template");
    }

    public boolean supports(Block block) {
        return block.getState() instanceof TileState;
    }

    public UUID readUuid(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            return null;
        }
        String raw = state.getPersistentDataContainer().get(uuidKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String readTemplateKey(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            return null;
        }
        return state.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
    }

    public void write(Block block, UUID uuid, String templateKeyValue) {
        if (!(block.getState() instanceof TileState state)) {
            throw new IllegalArgumentException("Block has no PersistentDataContainer (not a TileState): " + block.getType());
        }
        state.getPersistentDataContainer().set(uuidKey, PersistentDataType.STRING, uuid.toString());
        state.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, templateKeyValue);
        state.update();
    }

    public void strip(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            return;
        }
        state.getPersistentDataContainer().remove(uuidKey);
        state.getPersistentDataContainer().remove(templateKey);
        state.update();
    }
}
