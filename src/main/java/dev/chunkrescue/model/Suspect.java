package dev.chunkrescue.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Suspect {
    private final String id;
    private final String world;
    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusChunks;
    private final String reason;
    private final RescueAction action;
    private final Instant createdAt;
    private final List<String> evidence;

    public Suspect(String id, String world, int centerChunkX, int centerChunkZ, int radiusChunks,
                   String reason, RescueAction action, Instant createdAt, List<String> evidence) {
        this.id = id;
        this.world = world;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radiusChunks = Math.max(0, radiusChunks);
        this.reason = reason == null ? "UNKNOWN" : reason;
        this.action = action;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }

    public String id() { return id; }
    public String world() { return world; }
    public int centerChunkX() { return centerChunkX; }
    public int centerChunkZ() { return centerChunkZ; }
    public int radiusChunks() { return radiusChunks; }
    public String reason() { return reason; }
    public RescueAction action() { return action; }
    public Instant createdAt() { return createdAt; }
    public List<String> evidence() { return new ArrayList<>(evidence); }

    public List<ChunkPos> targetChunks(int maxChunks) {
        List<ChunkPos> out = new ArrayList<>();
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                out.add(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
                if (maxChunks > 0 && out.size() >= maxChunks) return out;
            }
        }
        return out;
    }
}
