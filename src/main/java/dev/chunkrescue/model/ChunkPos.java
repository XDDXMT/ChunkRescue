package dev.chunkrescue.model;

public record ChunkPos(int x, int z) {
    public RegionKey regionKey(String world) {
        return new RegionKey(world, Math.floorDiv(x, 32), Math.floorDiv(z, 32));
    }

    public int localMcaIndex() {
        return (x & 31) + ((z & 31) * 32);
    }
}
