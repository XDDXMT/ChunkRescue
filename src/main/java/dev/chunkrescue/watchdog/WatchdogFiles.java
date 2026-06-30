package dev.chunkrescue.watchdog;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

final class WatchdogFiles {
    private WatchdogFiles() { }

    static void writeForceStartupRescueFlag(JavaPlugin plugin, String reason, String target) throws IOException {
        Path state = runtimeState(plugin);
        Files.createDirectories(state);
        Files.writeString(state.resolve("force-startup-rescue.flag"),
            "createdAt: \"" + Instant.now() + "\"\n"
                + "reason: \"" + escape(reason) + "\"\n"
                + "target: \"" + escape(target) + "\"\n",
            StandardCharsets.UTF_8
        );
    }

    static Path runtimeState(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve("runtime-state");
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "'");
    }
}
