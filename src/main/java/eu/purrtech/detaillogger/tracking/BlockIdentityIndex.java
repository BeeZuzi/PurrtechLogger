package eu.purrtech.detaillogger.tracking;

import eu.purrtech.detaillogger.db.dao.BlockLocationRecord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identity for plain (non-TileState) tracked blocks lives purely in the DB, keyed by coordinates -
 * this in-memory index mirrors only currently loaded chunks' worth of it, populated on
 * {@code ChunkLoadEvent} and dropped on {@code ChunkUnloadEvent}, so RAM stays bounded to what's
 * actually loaded rather than the whole DB.
 */
public final class BlockIdentityIndex {

    public record Entry(String uuid, String templateKey) {
    }

    private record ChunkKey(String world, int chunkX, int chunkZ) {
    }

    private record BlockKey(int x, int y, int z) {
    }

    private final Map<ChunkKey, Map<BlockKey, Entry>> chunks = new ConcurrentHashMap<>();

    public Entry get(String world, int x, int y, int z) {
        Map<BlockKey, Entry> blocks = chunks.get(chunkKeyOf(world, x, z));
        return blocks == null ? null : blocks.get(new BlockKey(x, y, z));
    }

    public void put(String world, int x, int y, int z, String uuid, String templateKey) {
        chunks.computeIfAbsent(chunkKeyOf(world, x, z), k -> new ConcurrentHashMap<>())
                .put(new BlockKey(x, y, z), new Entry(uuid, templateKey));
    }

    public void remove(String world, int x, int y, int z) {
        Map<BlockKey, Entry> blocks = chunks.get(chunkKeyOf(world, x, z));
        if (blocks != null) {
            blocks.remove(new BlockKey(x, y, z));
        }
    }

    /** Relocates a tracked block's entry (e.g. pushed by a piston, or a falling block landing). */
    public void move(String world, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        Map<BlockKey, Entry> blocks = chunks.get(chunkKeyOf(world, fromX, fromZ));
        Entry entry = blocks == null ? null : blocks.remove(new BlockKey(fromX, fromY, fromZ));
        if (entry != null) {
            put(world, toX, toY, toZ, entry.uuid(), entry.templateKey());
        }
    }

    public void loadChunk(String world, int chunkX, int chunkZ, List<BlockLocationRecord> records) {
        Map<BlockKey, Entry> blocks = new ConcurrentHashMap<>();
        for (BlockLocationRecord r : records) {
            blocks.put(new BlockKey(r.x(), r.y(), r.z()), new Entry(r.unitUuid(), r.templateKey()));
        }
        chunks.put(new ChunkKey(world, chunkX, chunkZ), blocks);
    }

    public void unloadChunk(String world, int chunkX, int chunkZ) {
        chunks.remove(new ChunkKey(world, chunkX, chunkZ));
    }

    private static ChunkKey chunkKeyOf(String world, int x, int z) {
        return new ChunkKey(world, x >> 4, z >> 4);
    }
}
