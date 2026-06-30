package dev.chunkrescue.startup;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class RescueJournal {
    private final JavaPlugin plugin;
    private final Path journalFile;
    private final YamlConfiguration yaml = new YamlConfiguration();

    public RescueJournal(JavaPlugin plugin) {
        this.plugin = plugin;
        this.journalFile = plugin.getDataFolder().toPath().resolve("runtime-state").resolve("repair-journal.yml");
    }

    public boolean hasDirtyJournal() {
        if (!Files.exists(journalFile)) return false;
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(journalFile.toFile());
        String status = existing.getString("status", "UNKNOWN");
        if ("DONE".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status) || "FAILED_NO_BACKUP".equalsIgnoreCase(status)) {
            return false;
        }
        // Compatibility with 0.1.5/0.1.6: a FAILED journal without backup.files means
        // the repair aborted before any destructive patch step could run.
        if ("FAILED".equalsIgnoreCase(status) && !existing.contains("backup.files")) {
            return false;
        }
        return true;
    }

    public void begin(String repairId) throws IOException {
        Files.createDirectories(journalFile.getParent());
        yaml.set("id", repairId);
        yaml.set("status", "PREPARING");
        yaml.set("startedAt", Instant.now().toString());
        save();
    }

    public void status(String status) throws IOException {
        yaml.set("status", status);
        yaml.set("updatedAt", Instant.now().toString());
        save();
    }

    public void set(String path, Object value) throws IOException {
        yaml.set(path, value);
        save();
    }

    private void save() throws IOException {
        try {
            yaml.save(journalFile.toFile());
        } catch (IOException ex) {
            plugin.getLogger().severe("[ChunkRescue] Failed to save repair journal: " + ex.getMessage());
            throw ex;
        }
    }
}
