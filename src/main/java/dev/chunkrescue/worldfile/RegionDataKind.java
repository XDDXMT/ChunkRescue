package dev.chunkrescue.worldfile;

public enum RegionDataKind {
    REGION("region"),
    ENTITIES("entities"),
    POI("poi");

    private final String folderName;

    RegionDataKind(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }
}
