package dev.chunkrescue.watchdog;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.ChunkKey;
import dev.chunkrescue.model.RescueAction;
import dev.chunkrescue.monitor.RuntimeMonitor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionProbeWatchdog {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final RuntimeMonitor runtimeMonitor;
    private final HardStopWatchdog parent;
    private final Map<ChunkKey, Long> pending = new ConcurrentHashMap<>();
    private volatile long lastProbeCycleMillis = 0L;
    private volatile long lastTriggerMillis = 0L;

    public RegionProbeWatchdog(JavaPlugin plugin, RescueConfig config, RuntimeMonitor runtimeMonitor, HardStopWatchdog parent) {
        this.plugin = plugin;
        this.config = config;
        this.runtimeMonitor = runtimeMonitor;
        this.parent = parent;
    }

    public void check() {
        if (!config.regionProbeWatchdogEnabled()) return;
        long now = System.currentTimeMillis();
        scheduleProbeCycleIfNeeded(now);
        detectTimedOutProbes(now);
    }

    public String status() {
        return "region-probe=" + config.regionProbeWatchdogEnabled()
            + ", trackedLoadedChunks=" + runtimeMonitor.trackedLoadedChunkCount()
            + ", pendingProbes=" + pending.size();
    }

    private void scheduleProbeCycleIfNeeded(long now) {
        long intervalMs = config.regionProbeIntervalSeconds() * 1000L;
        if (now - lastProbeCycleMillis < intervalMs) return;
        lastProbeCycleMillis = now;

        List<ChunkKey> chunks = runtimeMonitor.loadedChunksSnapshot(config.regionProbeMaxProbesPerCycle());
        for (ChunkKey key : chunks) {
            if (pending.containsKey(key)) continue;
            World world = Bukkit.getWorld(key.world());
            if (world == null) continue;
            pending.put(key, now);
            try {
                Bukkit.getRegionScheduler().run(plugin, world, key.chunkX(), key.chunkZ(), task -> pending.remove(key));
            } catch (Throwable t) {
                pending.remove(key);
            }
        }
    }

    private void detectTimedOutProbes(long now) {
        long timeoutMs = config.regionProbeTimeoutSeconds() * 1000L;
        List<Map.Entry<ChunkKey, Long>> timedOut = new ArrayList<>();
        for (Map.Entry<ChunkKey, Long> entry : pending.entrySet()) {
            if (now - entry.getValue() >= timeoutMs) timedOut.add(entry);
        }
        if (timedOut.size() < config.regionProbeTimedOutProbesToTrigger()) return;

        if (now - lastTriggerMillis < 60_000L) return;
        lastTriggerMillis = now;

        List<ChunkKey> targets = selectTargets(timedOut);
        if (targets.isEmpty()) return;

        RescueAction action = config.regionProbeActionOnNextBoot();
        int radius = config.regionProbeSuspectRadiusChunks();
        List<String> targetText = new ArrayList<>();
        for (ChunkKey key : targets) {
            List<String> evidence = new ArrayList<>();
            evidence.add("detector=region-probe-watchdog");
            evidence.add("trackedLoadedChunks=" + runtimeMonitor.trackedLoadedChunkCount());
            evidence.add("pendingProbes=" + pending.size());
            evidence.add("timedOutProbes=" + timedOut.size());
            evidence.add("timeoutSeconds=" + config.regionProbeTimeoutSeconds());
            evidence.add("probeTarget=" + key.compact());
            try {
                runtimeMonitor.markSuspect("probe", key.world(), key.chunkX(), key.chunkZ(), radius,
                    "REGION_PROBE_TIMEOUT", action, evidence);
                targetText.add(key.compact() + " r=" + radius);
            } catch (IOException e) {
                plugin.getLogger().warning("[ChunkRescue] Failed to save probe suspect " + key.compact() + ": " + e.getMessage());
            }
        }

        if (targetText.isEmpty()) return;
        plugin.getLogger().severe("[ChunkRescue] Region probe watchdog triggered: timedOutProbes="
            + timedOut.size() + " targets=" + String.join(",", targetText));

        if (config.regionProbeForceStartupRescue()) {
            try {
                WatchdogFiles.writeForceStartupRescueFlag(plugin, "REGION_PROBE_TIMEOUT", String.join(",", targetText));
            } catch (IOException e) {
                plugin.getLogger().warning("[ChunkRescue] Failed to write force-startup-rescue.flag: " + e.getMessage());
            }
        }

        if (config.regionProbeShutdownOnTrigger()) {
            parent.triggerEmergencyStop("REGION_PROBE_TIMEOUT", timeoutMs);
        }
    }

    private List<ChunkKey> selectTargets(List<Map.Entry<ChunkKey, Long>> timedOut) {
        int max = config.regionProbeMaxSuspectsPerTrigger();
        LinkedHashSet<ChunkKey> out = new LinkedHashSet<>();
        Set<String> timedOutWorlds = new HashSet<>();
        for (Map.Entry<ChunkKey, Long> entry : timedOut) timedOutWorlds.add(entry.getKey().world());

        if (config.regionProbePreferRecentPlayerChunks()) {
            for (ChunkKey key : runtimeMonitor.recentPlayerChunksSnapshot(max, config.regionProbeRecentPlayerChunkSeconds())) {
                if (timedOutWorlds.contains(key.world())) out.add(key);
                if (out.size() >= max) return new ArrayList<>(out);
            }
        }

        Map<ChunkKey, Long> age = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<ChunkKey, Long> entry : timedOut) age.put(entry.getKey(), now - entry.getValue());
        age.entrySet().stream()
            .sorted(Map.Entry.<ChunkKey, Long>comparingByValue().reversed())
            .limit(max)
            .forEach(e -> out.add(e.getKey()));
        return new ArrayList<>(out);
    }
}
