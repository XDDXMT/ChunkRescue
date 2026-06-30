package dev.chunkrescue.worldfile;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.RegionKey;
import dev.chunkrescue.util.Hashing;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class McaBackupService {
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final WorldPathResolver resolver;

    public McaBackupService(JavaPlugin plugin, RescueConfig config, WorldPathResolver resolver) {
        this.plugin = plugin;
        this.config = config;
        this.resolver = resolver;
    }

    public BackupResult backupRegions(String repairId, Set<RegionKey> regions) throws IOException {
        Path backupRoot = plugin.getServer().getWorldContainer().toPath()
            .resolve(config.backupFolder())
            .resolve(repairId == null || repairId.isBlank() ? ID_FORMAT.format(LocalDateTime.now()) : repairId)
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(backupRoot);

        Map<Path, BackupEntry> entries = new LinkedHashMap<>();
        for (RegionKey key : regions) {
            for (Path file : resolver.existingRegionFiles(key)) {
                if (entries.containsKey(file)) continue;
                Path normalizedFile = file.toAbsolutePath().normalize();
                Path relative;
                try {
                    relative = resolver.worldContainer().relativize(normalizedFile);
                } catch (IllegalArgumentException outsideRoot) {
                    // Absolute world-path-overrides may point outside the server root.
                    // Keep the backup deterministic instead of failing during backup.
                    relative = Path.of("external-worlds").resolve(normalizedFile.getFileName());
                }
                Path target = backupRoot.resolve("original").resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                String sourceHash = Hashing.sha256(file);
                String backupHash = Hashing.sha256(target);
                if (config.verifyBackupSha256() && !sourceHash.equals(backupHash)) {
                    throw new IOException("Backup hash mismatch: " + file);
                }
                entries.put(file, new BackupEntry(file, target, sourceHash));
            }
        }
        return new BackupResult(backupRoot, entries);
    }

    public record BackupEntry(Path original, Path backup, String sha256) {}
    public record BackupResult(Path folder, Map<Path, BackupEntry> entries) {}
}
