package dev.chunkrescue.util;

public final class PlatformGuard {
    private PlatformGuard() {}

    public static boolean isFoliaLike() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
