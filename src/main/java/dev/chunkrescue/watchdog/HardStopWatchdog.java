package dev.chunkrescue.watchdog;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.monitor.RuntimeMonitor;
import dev.chunkrescue.util.ThreadDumper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class HardStopWatchdog {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final RuntimeMonitor runtimeMonitor;
    private final AtomicLong lastGlobalHeartbeat = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private ScheduledExecutorService dedicatedExecutor;
    private FoliaLogWatchdog foliaLogWatchdog;
    private RegionProbeWatchdog regionProbeWatchdog;

    public HardStopWatchdog(JavaPlugin plugin, RescueConfig config, RuntimeMonitor runtimeMonitor) {
        this.plugin = plugin;
        this.config = config;
        this.runtimeMonitor = runtimeMonitor;
    }

    public void start() {
        if (!config.hardStopEnabled()) return;
        startGlobalHeartbeat();
        this.foliaLogWatchdog = new FoliaLogWatchdog(plugin, config, runtimeMonitor, this);
        this.regionProbeWatchdog = new RegionProbeWatchdog(plugin, config, runtimeMonitor, this);
        if (config.regionLogWatchdogEnabled() || config.healthReportWatchdogEnabled()) {
            plugin.getLogger().info("[ChunkRescue] Folia log/health watchdog enabled: " + foliaLogWatchdog.status());
        }
        if (config.regionProbeWatchdogEnabled()) {
            plugin.getLogger().info("[ChunkRescue] Region probe watchdog enabled: " + regionProbeWatchdog.status());
        }
        if (config.dedicatedThreadEnabled()) {
            startDedicatedThread();
            plugin.getLogger().info("[ChunkRescue] Dedicated watchdog thread enabled.");
        } else {
            startAsyncSchedulerWatchdog();
            plugin.getLogger().info("[ChunkRescue] Dedicated watchdog thread disabled; using Folia async scheduler watchdog.");
        }
    }

    public void stop() {
        if (dedicatedExecutor != null) dedicatedExecutor.shutdownNow();
    }

    /**
     * Test-only hook. It does not lag the server; it only makes the watchdog believe
     * the global heartbeat is stale. Use it to validate emergency-stop files and halt behavior.
     */
    public void debugMakeHeartbeatStale(long millisAgo) {
        lastGlobalHeartbeat.set(System.currentTimeMillis() - Math.max(0L, millisAgo));
        plugin.getLogger().warning("[ChunkRescue] DEBUG: global heartbeat was made stale by " + millisAgo + "ms.");
    }

    /**
     * Test-only hook. Schedules a blocking task on Folia's global region scheduler.
     * The dedicated JVM watchdog thread should continue running and hard-stop the JVM.
     */
    public void debugFreezeGlobalScheduler(long seconds) {
        long clamped = Math.max(1L, seconds);
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            plugin.getLogger().warning("[ChunkRescue] DEBUG: freezing global scheduler for " + clamped + "s.");
            try {
                Thread.sleep(clamped * 1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().warning("[ChunkRescue] DEBUG: global scheduler freeze finished.");
        });
    }

    private void startGlobalHeartbeat() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            task -> lastGlobalHeartbeat.set(System.currentTimeMillis()),
            20L,
            20L
        );
    }

    private void startDedicatedThread() {
        dedicatedExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, config.dedicatedThreadName());
            t.setDaemon(config.dedicatedThreadDaemon());
            t.setPriority(config.dedicatedThreadPriority());
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    writeCrashMarker("WATCHDOG_THREAD_CRASH", 0, throwable.toString());
                } catch (Throwable ignored) {
                }
                if (config.haltOnWatchdogCrash()) {
                    Runtime.getRuntime().halt(config.haltCodeOnWatchdogCrash());
                }
            });
            return t;
        });
        dedicatedExecutor.scheduleAtFixedRate(this::checkSafely, 5, 1, TimeUnit.SECONDS);
    }

    private void startAsyncSchedulerWatchdog() {
        Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            task -> checkSafely(),
            5,
            1,
            TimeUnit.SECONDS
        );
    }

    private void checkSafely() {
        try {
            check();
        } catch (Throwable t) {
            plugin.getLogger().warning("[ChunkRescue] Watchdog check failed: " + t.getMessage());
        }
    }

    private void check() {
        if (foliaLogWatchdog != null) foliaLogWatchdog.check();
        if (regionProbeWatchdog != null) regionProbeWatchdog.check();

        long now = System.currentTimeMillis();
        long silentMs = now - lastGlobalHeartbeat.get();
        long timeoutMs = config.noGlobalHeartbeatTimeoutSeconds() * 1000L;
        if (silentMs >= timeoutMs) {
            triggerEmergencyStop("NO_GLOBAL_HEARTBEAT", silentMs);
        }
    }

    public void triggerEmergencyStop(String reason, long silentMs) {
        if (stopping.compareAndSet(false, true)) {
            emergencyStop(reason, silentMs);
        }
    }

    public String statusLine() {
        String log = foliaLogWatchdog == null ? "region-log=n/a" : foliaLogWatchdog.status();
        String probe = regionProbeWatchdog == null ? "region-probe=n/a" : regionProbeWatchdog.status();
        return log + " | " + probe;
    }

    private void emergencyStop(String reason, long silentMs) {
        try {
            if (config.flushSuspectsBeforeStop() && runtimeMonitor != null) runtimeMonitor.flushNow();
            if (config.writeCrashMarkerBeforeStop()) writeCrashMarker(reason, silentMs, null);
            if (config.createThreadDumpBeforeStop()) {
                Path dump = runtimeState().resolve("thread-dumps")
                    .resolve("thread-dump-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + ".txt");
                ThreadDumper.writeThreadDump(dump);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[ChunkRescue] Failed to write emergency files: " + t.getMessage());
        }

        if (config.gracefulShutdownFirst()) {
            try {
                plugin.getLogger().severe("[ChunkRescue] Emergency stop triggered. reason=" + reason + " silentMs=" + silentMs + ". Requesting shutdown.");
                Bukkit.shutdown();
            } catch (Throwable t) {
                plugin.getLogger().warning("[ChunkRescue] Bukkit.shutdown failed: " + t.getMessage());
            }
        }

        if (!config.forceHalt()) return;

        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(config.gracefulWaitSeconds() * 1000L);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(config.haltExitCode());
        }, "ChunkRescue-Force-Halt");
        killer.setDaemon(false);
        killer.start();
    }

    private void writeCrashMarker(String reason, long silentMs, String throwable) throws IOException {
        Path marker = runtimeState().resolve("crash-marker.yml");
        Files.createDirectories(marker.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("type: HARD_STOP_WATCHDOG\n");
        sb.append("reason: \"").append(escape(reason)).append("\"\n");
        sb.append("silentMs: ").append(silentMs).append('\n');
        sb.append("time: \"").append(Instant.now()).append("\"\n");
        sb.append("nextBootAction: STARTUP_RESCUE\n");
        if (throwable != null) sb.append("throwable: \"").append(escape(throwable)).append("\"\n");
        Files.writeString(marker, sb.toString(), StandardCharsets.UTF_8);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Path runtimeState() {
        return plugin.getDataFolder().toPath().resolve("runtime-state");
    }
}
