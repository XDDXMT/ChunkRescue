package dev.chunkrescue.model;

import java.util.Locale;

public enum RescueAction {
    DELETE_CHUNKS_REGENERATE,
    PURGE_REGION_ENTITIES,
    DELETE_REGION_REGENERATE,
    STOP_SERVER,

    // Reserved for later NBT patcher implementation.
    PURGE_ENTITIES_ONLY,
    DISABLE_MACHINE_BLOCKS,
    SAFE_VOID_CHUNK;

    public static RescueAction parse(String value, RescueAction fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return RescueAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public boolean implementedInMvp() {
        return this == DELETE_CHUNKS_REGENERATE
            || this == PURGE_REGION_ENTITIES
            || this == DELETE_REGION_REGENERATE
            || this == STOP_SERVER;
    }
}
