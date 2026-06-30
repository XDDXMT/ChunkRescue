package dev.chunkrescue.model;

public record ChunkKey(String world, int chunkX, int chunkZ) {
    public String compact() {
        return world + ":" + chunkX + ":" + chunkZ;
    }
}
