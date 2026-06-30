package dev.chunkrescue.monitor;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.ChunkKey;
import dev.chunkrescue.model.RescueAction;
import dev.chunkrescue.model.Suspect;
import dev.chunkrescue.startup.SuspectStore;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RuntimeMonitor implements Listener {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final SuspectStore suspectStore;
    private final Map<ChunkKey, ChunkCounters> counters = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Long> loadedChunks = new ConcurrentHashMap<>();
    private final Map<String, RecentPlayerChunk> recentPlayerChunks = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    public RuntimeMonitor(JavaPlugin plugin, RescueConfig config, SuspectStore suspectStore) {
        this.plugin = plugin;
        this.config = config;
        this.suspectStore = suspectStore;
    }

    public void start() {
        if (!config.runtimeMonitorEnabled()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChunkRescue-RuntimeMonitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::flushSafely,
            config.runtimeFlushIntervalSeconds(),
            config.runtimeFlushIntervalSeconds(),
            TimeUnit.SECONDS);
        plugin.getLogger().info("[ChunkRescue] Runtime suspect monitor enabled.");
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    public void flushNow() {
        flushSafely();
    }

    public List<ChunkKey> loadedChunksSnapshot(int max) {
        int limit = Math.max(1, max);
        List<ChunkKey> out = new ArrayList<>(Math.min(loadedChunks.size(), limit));
        long now = System.currentTimeMillis();
        loadedChunks.entrySet().stream()
            .sorted(Map.Entry.<ChunkKey, Long>comparingByValue(Comparator.reverseOrder()))
            .limit(limit)
            .forEach(e -> {
                if (now - e.getValue() < TimeUnit.MINUTES.toMillis(30)) out.add(e.getKey());
            });
        return out;
    }

    public List<ChunkKey> recentPlayerChunksSnapshot(int max, int maxAgeSeconds) {
        int limit = Math.max(1, max);
        long cutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(Math.max(1, maxAgeSeconds));
        List<ChunkKey> out = new ArrayList<>();
        recentPlayerChunks.values().stream()
            .filter(v -> v.lastSeenMillis >= cutoff)
            .sorted(Comparator.comparingLong((RecentPlayerChunk v) -> v.lastSeenMillis).reversed())
            .limit(limit)
            .forEach(v -> out.add(v.key));
        return out;
    }

    public int trackedLoadedChunkCount() {
        return loadedChunks.size();
    }

    public void markSuspect(String idPrefix, String world, int chunkX, int chunkZ, int radius, String reason, RescueAction action, List<String> evidence) throws IOException {
        String prefix = (idPrefix == null || idPrefix.isBlank()) ? "auto" : idPrefix;
        Suspect suspect = new Suspect(
            prefix + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8),
            world, chunkX, chunkZ, Math.max(0, radius), reason, action, Instant.now(), evidence
        );
        suspectStore.add(suspect);
        plugin.getLogger().severe("[ChunkRescue] Marked suspect chunk " + world + ":" + chunkX + ":" + chunkZ
            + " r=" + Math.max(0, radius) + " reason=" + reason + " action=" + action);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        loadedChunks.put(new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        loadedChunks.remove(new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        rememberPlayerChunk(event.getPlayer().getName(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
            && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
            && from.getWorld() == to.getWorld()) return;
        rememberPlayerChunk(event.getPlayer().getName(), to);
    }

    private void rememberPlayerChunk(String playerName, Location location) {
        if (location == null || location.getWorld() == null) return;
        ChunkKey key = key(location);
        loadedChunks.put(key, System.currentTimeMillis());
        recentPlayerChunks.put(playerName, new RecentPlayerChunk(key, System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        ChunkCounters c = counters.computeIfAbsent(key(entity.getLocation()), ignored -> new ChunkCounters());
        c.entitySpawns.increment();
        String typeName = entity.getType().name();
        // Keep this string-based to compile across Folia forks / Bukkit enum rename differences.
        if ("TNT_MINECART".equals(typeName) || "MINECART_TNT".equals(typeName)) c.tntMinecartSpawns.increment();
        if ("FALLING_BLOCK".equals(typeName)) c.fallingBlockSpawns.increment();
        if ("ITEM".equals(typeName)) c.itemSpawns.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        counters.computeIfAbsent(key(event.getBlock()), ignored -> new ChunkCounters()).pistonEvents.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        counters.computeIfAbsent(key(event.getBlock()), ignored -> new ChunkCounters()).pistonEvents.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        counters.computeIfAbsent(key(event.getBlock()), ignored -> new ChunkCounters()).redstoneEvents.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        counters.computeIfAbsent(key(event.getBlock()), ignored -> new ChunkCounters()).dispenserEvents.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        counters.computeIfAbsent(key(event.getLocation()), ignored -> new ChunkCounters()).explosionEvents.increment();
    }

    private ChunkKey key(Location loc) {
        return new ChunkKey(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private ChunkKey key(Block block) {
        return new ChunkKey(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            plugin.getLogger().warning("[ChunkRescue] Failed to flush runtime monitor: " + t.getMessage());
        }
    }

    private void flush() {
        for (Map.Entry<ChunkKey, ChunkCounters> entry : counters.entrySet()) {
            ChunkCounters.Snapshot s = entry.getValue().snapshotThenReset();
            String reason = detectReason(s);
            if (reason == null) continue;

            RescueAction action = actionFor(reason);
            ChunkKey key = entry.getKey();
            List<String> evidence = new ArrayList<>();
            evidence.add("entitySpawns=" + s.entitySpawns());
            evidence.add("tntMinecartSpawns=" + s.tntMinecartSpawns());
            evidence.add("fallingBlockSpawns=" + s.fallingBlockSpawns());
            evidence.add("itemSpawns=" + s.itemSpawns());
            evidence.add("pistonEvents=" + s.pistonEvents());
            evidence.add("redstoneEvents=" + s.redstoneEvents());
            evidence.add("dispenserEvents=" + s.dispenserEvents());
            evidence.add("explosionEvents=" + s.explosionEvents());

            Suspect suspect = new Suspect(
                "auto_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8),
                key.world(),
                key.chunkX(),
                key.chunkZ(),
                config.machineRadiusChunks(),
                reason,
                action,
                Instant.now(),
                evidence
            );
            try {
                suspectStore.add(suspect);
                plugin.getLogger().severe("[ChunkRescue] Marked suspect chunk " + key.compact() + " reason=" + reason + " action=" + action);
            } catch (IOException e) {
                plugin.getLogger().warning("[ChunkRescue] Failed to save suspect: " + e.getMessage());
            }
        }
    }

    private String detectReason(ChunkCounters.Snapshot s) {
        if (s.tntMinecartSpawns() >= config.thresholdTntMinecartSpawns()) return "TNT_MINECART_MACHINE";
        if (s.entitySpawns() >= config.thresholdEntitySpawns()) return "TOO_MANY_ENTITIES";
        if (s.fallingBlockSpawns() >= config.thresholdFallingBlockSpawns()) return "FALLING_BLOCK_MACHINE";
        if (s.itemSpawns() >= config.thresholdItemSpawns()) return "ITEM_ENTITY_MACHINE";
        if (s.pistonEvents() >= config.thresholdPistonEvents() || s.redstoneEvents() >= config.thresholdRedstoneEvents()) return "PISTON_REDSTONE_MACHINE";
        if (s.dispenserEvents() >= config.thresholdDispenserEvents()) return "DISPENSER_MACHINE";
        if (s.explosionEvents() >= config.thresholdExplosionEvents()) return "EXPLOSION_STORM";
        return null;
    }

    private RescueAction actionFor(String reason) {
        return switch (reason) {
            case "TNT_MINECART_MACHINE" -> config.actionTntMachine();
            case "PISTON_REDSTONE_MACHINE" -> config.actionPistonMachine();
            case "TOO_MANY_ENTITIES" -> config.actionTooManyEntities();
            default -> config.actionUnknownHeavyMachine();
        };
    }

    private record RecentPlayerChunk(ChunkKey key, long lastSeenMillis) { }

}