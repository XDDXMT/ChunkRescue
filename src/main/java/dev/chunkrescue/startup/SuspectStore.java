package dev.chunkrescue.startup;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.RescueAction;
import dev.chunkrescue.model.Suspect;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SuspectStore {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final Path file;

    public SuspectStore(JavaPlugin plugin, RescueConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = plugin.getDataFolder().toPath().resolve("suspects.yml");
    }

    public synchronized List<Suspect> loadPending() {
        List<Suspect> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection sec = yaml.getConfigurationSection("suspects");
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            String base = "suspects." + id + ".";
            String status = yaml.getString(base + "status", "PENDING");
            if (!"PENDING".equalsIgnoreCase(status)) continue;
            String world = yaml.getString(base + "world");
            if (world == null || world.isBlank()) continue;
            int cx = yaml.getInt(base + "centerChunkX");
            int cz = yaml.getInt(base + "centerChunkZ");
            int radius = yaml.getInt(base + "radiusChunks", config.machineRadiusChunks());
            String reason = yaml.getString(base + "reason", "UNKNOWN");
            RescueAction action = RescueAction.parse(yaml.getString(base + "actionOnNextBoot"), config.defaultAction());
            Instant created;
            try {
                created = Instant.parse(yaml.getString(base + "createdAt", Instant.now().toString()));
            } catch (Exception e) {
                created = Instant.now();
            }
            List<String> evidence = yaml.getStringList(base + "evidence");
            out.add(new Suspect(id, world, cx, cz, radius, reason, action, created, evidence));
        }
        return out;
    }

    public synchronized void add(Suspect suspect) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = Files.exists(file) ? YamlConfiguration.loadConfiguration(file.toFile()) : new YamlConfiguration();
        String id = suspect.id() == null || suspect.id().isBlank() ? UUID.randomUUID().toString() : suspect.id();
        String base = "suspects." + id + ".";
        yaml.set(base + "world", suspect.world());
        yaml.set(base + "centerChunkX", suspect.centerChunkX());
        yaml.set(base + "centerChunkZ", suspect.centerChunkZ());
        yaml.set(base + "radiusChunks", suspect.radiusChunks());
        yaml.set(base + "reason", suspect.reason());
        yaml.set(base + "actionOnNextBoot", suspect.action().name());
        yaml.set(base + "createdAt", suspect.createdAt().toString());
        yaml.set(base + "evidence", suspect.evidence());
        yaml.set(base + "status", "PENDING");
        yaml.save(file.toFile());
    }

    public synchronized void markDone(List<Suspect> suspects, String repairId) throws IOException {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (Suspect suspect : suspects) {
            String base = "suspects." + suspect.id() + ".";
            yaml.set(base + "status", "DONE");
            yaml.set(base + "repairId", repairId);
            yaml.set(base + "finishedAt", Instant.now().toString());
        }
        yaml.save(file.toFile());
    }

    public synchronized Path path() {
        return file;
    }
}
