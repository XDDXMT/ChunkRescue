package dev.chunkrescue.model;

public record RegionKey(String world, int regionX, int regionZ) {
    public String fileName() {
        return "r." + regionX + "." + regionZ + ".mca";
    }
}
