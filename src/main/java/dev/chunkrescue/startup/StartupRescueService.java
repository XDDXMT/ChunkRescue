package dev.chunkrescue.startup;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.ChunkPos;
import dev.chunkrescue.model.RegionKey;
import dev.chunkrescue.model.RescueAction;
import dev.chunkrescue.model.Suspect;
import dev.chunkrescue.worldfile.McaBackupService;
import dev.chunkrescue.worldfile.McaChunkEditor;
import dev.chunkrescue.worldfile.RegionDataKind;
import dev.chunkrescue.worldfile.WorldPathResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class StartupRescueService {
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final SuspectStore suspectStore;
    private final WorldPathResolver resolver;
    private final McaBackupService backupService;
    private final McaChunkEditor editor = new McaChunkEditor();

    public StartupRescueService(JavaPlugin plugin, RescueConfig config, SuspectStore suspectStore) {
        this.plugin = plugin;
        this.config = config;
        this.suspectStore = suspectStore;
        this.resolver = new WorldPathResolver(plugin);
        this.backupService = new McaBackupService(plugin, config, resolver);
    }

    public void runStartupRescueIfNeeded() {
        if (!config.startupRescueEnabled()) return;
        if (!"ANVIL_MCA_ONLY".equalsIgnoreCase(config.storageFormat())) {
            plugin.getLogger().severe("[ChunkRescue] Unsupported storage-format: " + config.storageFormat() + ". Refusing to patch world files.");
            return;
        }

        Path runningFlag = runtimeState().resolve("server-running.flag");
        Path crashMarker = runtimeState().resolve("crash-marker.yml");
        Path forceFlag = runtimeState().resolve("force-startup-rescue.flag");
        boolean unclean = Files.exists(runningFlag) || Files.exists(crashMarker);
        boolean forced = Files.exists(forceFlag);
        if (forced) {
            plugin.getLogger().warning("[ChunkRescue] force-startup-rescue.flag found; startup rescue will run even after a clean shutdown.");
        }
        if (config.onlyAfterUncleanShutdown() && !unclean && !forced) {
            plugin.getLogger().info("[ChunkRescue] Previous shutdown looks clean; startup rescue skipped.");
            return;
        }

        RescueJournal journal = new RescueJournal(plugin);
        if (journal.hasDirtyJournal()) {
            plugin.getLogger().severe("[ChunkRescue] Dirty repair journal found. Refusing to continue automatically.");
            if (config.stopServerIfJournalDirty()) Bukkit.shutdown();
            return;
        }

        List<Suspect> pending = suspectStore.loadPending();
        if (pending.isEmpty()) {
            plugin.getLogger().info("[ChunkRescue] No pending suspect chunks; startup rescue skipped.");
            return;
        }

        for (Suspect s : pending) {
            if (config.refuseIfWorldLoaded() && Bukkit.getWorld(s.world()) != null) {
                plugin.getLogger().severe("[ChunkRescue] World appears loaded already: " + s.world() + ". Refusing file-level rescue.");
                Bukkit.shutdown();
                return;
            }
        }

        String repairId = "repair_" + ID_FORMAT.format(LocalDateTime.now());
        try {
            journal.begin(repairId);
            doRepair(repairId, pending, journal);
            journal.status("DONE");
            suspectStore.markDone(pending, repairId);
            Files.deleteIfExists(runtimeState().resolve("force-startup-rescue.flag"));
            plugin.getLogger().severe("[ChunkRescue] Startup rescue completed: " + repairId);
        } catch (Exception ex) {
            plugin.getLogger().severe("[ChunkRescue] Startup rescue failed: " + ex.getMessage());
            ex.printStackTrace();
            try {
                if (ex.getMessage() != null && ex.getMessage().startsWith("No MCA files were backed up")) {
                    journal.status("FAILED_NO_BACKUP");
                } else {
                    journal.status("FAILED");
                }
            } catch (IOException ignored) {}
            if (config.stopServerIfBackupFailed()) Bukkit.shutdown();
        }
    }

    private void doRepair(String repairId, List<Suspect> suspects, RescueJournal journal) throws IOException {
        Map<Suspect, List<ChunkPos>> targets = new LinkedHashMap<>();
        Set<RegionKey> affectedRegions = new LinkedHashSet<>();

        int remaining = config.maxChunksPerBoot();
        for (Suspect suspect : suspects) {
            int allowed = Math.max(0, remaining);
            if (allowed == 0) break;
            List<ChunkPos> chunks = suspect.targetChunks(allowed);
            remaining -= chunks.size();
            targets.put(suspect, chunks);
            for (ChunkPos chunk : chunks) affectedRegions.add(chunk.regionKey(suspect.world()));
        }

        journal.set("targets.count", targets.size());
        journal.set("regions.count", affectedRegions.size());
        journal.status("BACKING_UP");
        McaBackupService.BackupResult backup = backupService.backupRegions(repairId, affectedRegions);
        if (config.backupRequired() && backup.entries().isEmpty()) {
            plugin.getLogger().severe("[ChunkRescue] No MCA files were found for the pending suspect regions. Diagnostics follow:");
            int shown = 0;
            for (RegionKey key : affectedRegions) {
                plugin.getLogger().severe("[ChunkRescue] Lookup diagnostics for " + key.world() + " " + key.fileName() + ":\n" + resolver.diagnostics(key));
                if (++shown >= 3) {
                    if (affectedRegions.size() > shown) plugin.getLogger().severe("[ChunkRescue] " + (affectedRegions.size() - shown) + " more affected regions omitted from diagnostics.");
                    break;
                }
            }
            throw new IOException("No MCA files were backed up. The target world folder/region file was not found, or the world uses a non-Anvil storage format.");
        }
        journal.set("backup.folder", backup.folder().toString());
        journal.set("backup.files", backup.entries().keySet().stream().map(Path::toString).collect(Collectors.toList()));
        journal.status("BACKUP_OK");

        Map<String, Object> reportValues = new LinkedHashMap<>();
        int totalChunkRecordsDeleted = 0;
        int totalFilesDeleted = 0;

        for (Map.Entry<Suspect, List<ChunkPos>> entry : targets.entrySet()) {
            Suspect suspect = entry.getKey();
            List<ChunkPos> chunks = entry.getValue();
            RescueAction action = suspect.action().implementedInMvp() ? suspect.action() : config.defaultAction();
            if (!action.implementedInMvp()) action = RescueAction.DELETE_CHUNKS_REGENERATE;

            if (action == RescueAction.STOP_SERVER) {
                throw new IOException("Suspect action is STOP_SERVER: " + suspect.id());
            }

            if (action == RescueAction.DELETE_CHUNKS_REGENERATE) {
                totalChunkRecordsDeleted += deleteChunkRecordsForSuspect(suspect, chunks, true, true, true);
            } else if (action == RescueAction.PURGE_REGION_ENTITIES) {
                totalFilesDeleted += deleteEntityRegionFiles(suspect, chunks);
            } else if (action == RescueAction.DELETE_REGION_REGENERATE) {
                totalFilesDeleted += deleteWholeRegionFiles(suspect, chunks);
            }

            reportValues.put(suspect.id() + ".world", suspect.world());
            reportValues.put(suspect.id() + ".center", suspect.centerChunkX() + "," + suspect.centerChunkZ());
            reportValues.put(suspect.id() + ".radius", suspect.radiusChunks());
            reportValues.put(suspect.id() + ".action", action.name());
            reportValues.put(suspect.id() + ".reason", suspect.reason());
        }

        journal.set("patched.chunkRecordsDeleted", totalChunkRecordsDeleted);
        journal.set("patched.filesDeleted", totalFilesDeleted);
        journal.status("PATCH_OK");

        if (config.writeHumanReport()) {
            writeReport(backup.folder(), repairId, suspects, totalChunkRecordsDeleted, totalFilesDeleted, reportValues);
        }
    }

    private int deleteChunkRecordsForSuspect(Suspect suspect, List<ChunkPos> chunks,
                                             boolean region, boolean entities, boolean poi) throws IOException {
        int changed = 0;
        Map<RegionKey, List<ChunkPos>> byRegion = groupByRegion(suspect.world(), chunks);
        for (Map.Entry<RegionKey, List<ChunkPos>> entry : byRegion.entrySet()) {
            RegionKey key = entry.getKey();
            if (region) changed += editor.deleteChunkRecords(resolver.existingRegionFile(key, RegionDataKind.REGION).orElse(null), entry.getValue());
            if (entities) changed += editor.deleteChunkRecords(resolver.existingRegionFile(key, RegionDataKind.ENTITIES).orElse(null), entry.getValue());
            if (poi) changed += editor.deleteChunkRecords(resolver.existingRegionFile(key, RegionDataKind.POI).orElse(null), entry.getValue());
        }
        return changed;
    }

    private int deleteEntityRegionFiles(Suspect suspect, List<ChunkPos> chunks) throws IOException {
        int deleted = 0;
        for (RegionKey key : groupByRegion(suspect.world(), chunks).keySet()) {
            Path f = resolver.existingRegionFile(key, RegionDataKind.ENTITIES).orElse(null);
            if (editor.deleteWholeRegionFile(f)) deleted++;
        }
        return deleted;
    }

    private int deleteWholeRegionFiles(Suspect suspect, List<ChunkPos> chunks) throws IOException {
        int deleted = 0;
        for (RegionKey key : groupByRegion(suspect.world(), chunks).keySet()) {
            for (Path f : resolver.existingRegionFiles(key)) {
                if (editor.deleteWholeRegionFile(f)) deleted++;
            }
        }
        return deleted;
    }

    private Map<RegionKey, List<ChunkPos>> groupByRegion(String world, List<ChunkPos> chunks) {
        Map<RegionKey, List<ChunkPos>> map = new LinkedHashMap<>();
        for (ChunkPos chunk : chunks) {
            map.computeIfAbsent(chunk.regionKey(world), ignored -> new ArrayList<>()).add(chunk);
        }
        return map;
    }

    private void writeReport(Path backupFolder, String repairId, List<Suspect> suspects,
                             int chunkRecordsDeleted, int filesDeleted, Map<String, Object> values) throws IOException {
        Path report = backupFolder.resolve("report.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("ChunkRescue Repair Report\n");
        sb.append("=========================\n\n");
        sb.append("Repair ID: ").append(repairId).append('\n');
        sb.append("Chunk records deleted: ").append(chunkRecordsDeleted).append('\n');
        sb.append("Whole files deleted: ").append(filesDeleted).append("\n\n");
        for (Suspect suspect : suspects) {
            sb.append("Suspect: ").append(suspect.id()).append('\n');
            sb.append("  World: ").append(suspect.world()).append('\n');
            sb.append("  Center chunk: ").append(suspect.centerChunkX()).append(',').append(suspect.centerChunkZ()).append('\n');
            sb.append("  Radius: ").append(suspect.radiusChunks()).append('\n');
            sb.append("  Action: ").append(suspect.action()).append('\n');
            sb.append("  Reason: ").append(suspect.reason()).append('\n');
            sb.append("  Evidence: ").append(suspect.evidence()).append("\n\n");
        }
        Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
    }

    private Path runtimeState() {
        return plugin.getDataFolder().toPath().resolve("runtime-state");
    }
}
