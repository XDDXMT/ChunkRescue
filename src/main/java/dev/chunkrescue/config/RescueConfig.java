package dev.chunkrescue.config;

import dev.chunkrescue.model.RescueAction;
import org.bukkit.configuration.file.FileConfiguration;

public final class RescueConfig {
    private final FileConfiguration config;

    public RescueConfig(FileConfiguration config) {
        this.config = config;
    }

    public boolean startupRescueEnabled() { return config.getBoolean("startup-rescue.enabled", true); }
    public boolean onlyAfterUncleanShutdown() { return config.getBoolean("startup-rescue.only-after-unclean-shutdown", true); }
    public boolean backupRequired() { return config.getBoolean("startup-rescue.backup-required", true); }
    public boolean verifyBackupSha256() { return config.getBoolean("startup-rescue.verify-backup-sha256", true); }
    public boolean refuseIfWorldLoaded() { return config.getBoolean("startup-rescue.refuse-if-world-loaded", true); }
    public RescueAction defaultAction() { return RescueAction.parse(config.getString("startup-rescue.default-action"), RescueAction.DELETE_CHUNKS_REGENERATE); }
    public int machineRadiusChunks() { return Math.max(0, config.getInt("startup-rescue.machine-radius-chunks", 2)); }
    public int maxChunksPerBoot() { return Math.max(1, config.getInt("startup-rescue.max-chunks-per-boot", 64)); }
    public String storageFormat() { return config.getString("startup-rescue.storage-format", "ANVIL_MCA_ONLY"); }

    public String backupFolder() { return config.getString("backup.folder", "plugins/ChunkRescue/backups"); }

    public boolean runtimeMonitorEnabled() { return config.getBoolean("runtime-monitor.enabled", true); }
    public int runtimeFlushIntervalSeconds() { return Math.max(1, config.getInt("runtime-monitor.flush-interval-seconds", 10)); }

    public boolean runtimeDedupeEnabled() { return config.getBoolean("runtime-monitor.marking.dedupe-enabled", true); }
    public int runtimeDuplicateCooldownSeconds() { return Math.max(0, config.getInt("runtime-monitor.marking.duplicate-cooldown-seconds", 300)); }
    public boolean runtimeLogDuplicateMarks() { return config.getBoolean("runtime-monitor.marking.log-duplicate-marks", false); }
    public int runtimeDuplicateLogEvery() { return Math.max(1, config.getInt("runtime-monitor.marking.duplicate-log-every", 12)); }
    public int runtimeMaxNewSuspectsPerFlush() { return Math.max(1, config.getInt("runtime-monitor.marking.max-new-suspects-per-flush", 16)); }
    public String runtimeNewSuspectLogLevel() { return config.getString("runtime-monitor.marking.new-suspect-log-level", "WARNING"); }

    public boolean runtimeCriticalStopEnabled() { return config.getBoolean("runtime-monitor.critical-stop.enabled", true); }
    public int runtimeCriticalRepeatsToTrigger() { return Math.max(1, config.getInt("runtime-monitor.critical-stop.repeats-to-trigger", 3)); }
    public int runtimeCriticalWindowSeconds() { return Math.max(5, config.getInt("runtime-monitor.critical-stop.window-seconds", 60)); }
    public boolean runtimeCriticalForceStartupRescue() { return config.getBoolean("runtime-monitor.critical-stop.force-startup-rescue", true); }
    public boolean runtimeCriticalShutdownOnTrigger() { return config.getBoolean("runtime-monitor.critical-stop.shutdown-on-trigger", true); }
    public java.util.Set<String> runtimeCriticalReasons() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String value : config.getStringList("runtime-monitor.critical-stop.reasons")) {
            if (value != null && !value.isBlank()) out.add(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (out.isEmpty()) {
            out.add("EXPLOSION_STORM");
            out.add("TNT_MINECART_MACHINE");
            out.add("TOO_MANY_ENTITIES");
            out.add("FALLING_BLOCK_MACHINE");
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    public int thresholdEntitySpawns() { return config.getInt("runtime-monitor.suspect-thresholds.entity-spawns-per-10s", 5000); }
    public int thresholdTntMinecartSpawns() { return config.getInt("runtime-monitor.suspect-thresholds.tnt-minecart-spawns-per-10s", 200); }
    public int thresholdFallingBlockSpawns() { return config.getInt("runtime-monitor.suspect-thresholds.falling-block-spawns-per-10s", 1000); }
    public int thresholdItemSpawns() { return config.getInt("runtime-monitor.suspect-thresholds.item-spawns-per-10s", 10000); }
    public int thresholdPistonEvents() { return config.getInt("runtime-monitor.suspect-thresholds.piston-events-per-10s", 5000); }
    public int thresholdRedstoneEvents() { return config.getInt("runtime-monitor.suspect-thresholds.redstone-events-per-10s", 12000); }
    public int thresholdDispenserEvents() { return config.getInt("runtime-monitor.suspect-thresholds.dispenser-events-per-10s", 3000); }
    public int thresholdExplosionEvents() { return config.getInt("runtime-monitor.suspect-thresholds.explosion-events-per-10s", 200); }

    public RescueAction actionTooManyEntities() { return RescueAction.parse(config.getString("runtime-monitor.auto-mark-action.too-many-entities"), defaultAction()); }
    public RescueAction actionTntMachine() { return RescueAction.parse(config.getString("runtime-monitor.auto-mark-action.tnt-machine"), defaultAction()); }
    public RescueAction actionPistonMachine() { return RescueAction.parse(config.getString("runtime-monitor.auto-mark-action.piston-redstone-machine"), defaultAction()); }
    public RescueAction actionUnknownHeavyMachine() { return RescueAction.parse(config.getString("runtime-monitor.auto-mark-action.unknown-heavy-machine"), defaultAction()); }

    public boolean dedicatedThreadEnabled() { return config.getBoolean("dedicated-watchdog-thread.enabled", true); }
    public String dedicatedThreadName() { return config.getString("dedicated-watchdog-thread.thread-name", "ChunkRescue-Dedicated-Watchdog"); }
    public int dedicatedThreadPriority() { return Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, config.getInt("dedicated-watchdog-thread.priority", 7))); }
    public boolean dedicatedThreadDaemon() { return config.getBoolean("dedicated-watchdog-thread.daemon", false); }
    public boolean haltOnWatchdogCrash() { return config.getBoolean("dedicated-watchdog-thread.halt-on-thread-crash", true); }
    public int haltCodeOnWatchdogCrash() { return config.getInt("dedicated-watchdog-thread.halt-exit-code-on-thread-crash", 138); }

    public boolean hardStopEnabled() { return config.getBoolean("hard-stop-watchdog.enabled", true); }
    public int noGlobalHeartbeatTimeoutSeconds() { return Math.max(5, config.getInt("hard-stop-watchdog.no-global-heartbeat-timeout-seconds", 30)); }
    public boolean gracefulShutdownFirst() { return config.getBoolean("hard-stop-watchdog.graceful-shutdown-first", true); }
    public int gracefulWaitSeconds() { return Math.max(0, config.getInt("hard-stop-watchdog.graceful-wait-seconds", 10)); }
    public boolean forceHalt() { return config.getBoolean("hard-stop-watchdog.force-halt", true); }
    public int haltExitCode() { return config.getInt("hard-stop-watchdog.halt-exit-code", 137); }

    public boolean writeCrashMarkerBeforeStop() { return config.getBoolean("hard-stop-watchdog.before-stop.write-crash-marker", true); }
    public boolean flushSuspectsBeforeStop() { return config.getBoolean("hard-stop-watchdog.before-stop.flush-suspects", true); }
    public boolean createThreadDumpBeforeStop() { return config.getBoolean("hard-stop-watchdog.before-stop.create-thread-dump", true); }


    public boolean regionLogWatchdogEnabled() { return config.getBoolean("hard-stop-watchdog.region-log-watchdog.enabled", true); }
    public String regionLogWatchdogPath() { return config.getString("hard-stop-watchdog.region-log-watchdog.log-path", "logs/latest.log"); }
    public int regionLogConsecutiveReportsToTrigger() { return Math.max(1, config.getInt("hard-stop-watchdog.region-log-watchdog.consecutive-reports-to-trigger", 2)); }
    public int regionLogTotalReportsToTrigger() { return Math.max(1, config.getInt("hard-stop-watchdog.region-log-watchdog.total-reports-to-trigger", 3)); }
    public int regionLogWindowSeconds() { return Math.max(5, config.getInt("hard-stop-watchdog.region-log-watchdog.window-seconds", 60)); }
    public double regionLogMinReportedSeconds() { return Math.max(0.0, config.getDouble("hard-stop-watchdog.region-log-watchdog.min-reported-seconds", 5.0)); }
    public int regionLogMaxSuspectsPerTrigger() { return Math.max(1, config.getInt("hard-stop-watchdog.region-log-watchdog.max-suspects-per-trigger", 4)); }
    public int regionLogSuspectRadiusChunks() { return Math.max(0, config.getInt("hard-stop-watchdog.region-log-watchdog.suspect-radius-chunks", machineRadiusChunks())); }
    public RescueAction regionLogActionOnNextBoot() { return RescueAction.parse(config.getString("hard-stop-watchdog.region-log-watchdog.action-on-next-boot"), defaultAction()); }
    public boolean regionLogForceStartupRescue() { return config.getBoolean("hard-stop-watchdog.region-log-watchdog.force-startup-rescue", true); }
    public boolean regionLogShutdownOnTrigger() { return config.getBoolean("hard-stop-watchdog.region-log-watchdog.shutdown-on-trigger", true); }

    public boolean healthReportWatchdogEnabled() { return config.getBoolean("hard-stop-watchdog.health-report-watchdog.enabled", true); }
    public double healthReportLowestTpsToTrigger() { return Math.max(0.0, config.getDouble("hard-stop-watchdog.health-report-watchdog.lowest-region-tps-to-trigger", 1.0)); }
    public double healthReportMsptToTrigger() { return Math.max(1.0, config.getDouble("hard-stop-watchdog.health-report-watchdog.region-mspt-to-trigger", 1000.0)); }
    public int healthReportEntityCountToTrigger() { return Math.max(0, config.getInt("hard-stop-watchdog.health-report-watchdog.region-entities-to-trigger", 10000)); }
    public int healthReportSuspectRadiusChunks() { return Math.max(0, config.getInt("hard-stop-watchdog.health-report-watchdog.suspect-radius-chunks", machineRadiusChunks())); }
    public RescueAction healthReportActionOnNextBoot() { return RescueAction.parse(config.getString("hard-stop-watchdog.health-report-watchdog.action-on-next-boot"), defaultAction()); }
    public boolean healthReportForceStartupRescue() { return config.getBoolean("hard-stop-watchdog.health-report-watchdog.force-startup-rescue", true); }
    public boolean healthReportShutdownOnTrigger() { return config.getBoolean("hard-stop-watchdog.health-report-watchdog.shutdown-on-trigger", true); }
    public String healthReportCoordinateMode() { return config.getString("hard-stop-watchdog.health-report-watchdog.coordinate-mode", "BLOCK"); }

    public boolean regionProbeWatchdogEnabled() { return config.getBoolean("hard-stop-watchdog.region-probe-watchdog.enabled", true); }
    public int regionProbeIntervalSeconds() { return Math.max(1, config.getInt("hard-stop-watchdog.region-probe-watchdog.interval-seconds", 5)); }
    public int regionProbeTimeoutSeconds() { return Math.max(3, config.getInt("hard-stop-watchdog.region-probe-watchdog.timeout-seconds", 20)); }
    public int regionProbeMaxProbesPerCycle() { return Math.max(1, config.getInt("hard-stop-watchdog.region-probe-watchdog.max-probes-per-cycle", 512)); }
    public int regionProbeTimedOutProbesToTrigger() { return Math.max(1, config.getInt("hard-stop-watchdog.region-probe-watchdog.timed-out-probes-to-trigger", 8)); }
    public int regionProbeSuspectRadiusChunks() { return Math.max(0, config.getInt("hard-stop-watchdog.region-probe-watchdog.suspect-radius-chunks", machineRadiusChunks())); }
    public boolean regionProbePreferRecentPlayerChunks() { return config.getBoolean("hard-stop-watchdog.region-probe-watchdog.prefer-recent-player-chunks", true); }
    public int regionProbeMaxSuspectsPerTrigger() { return Math.max(1, config.getInt("hard-stop-watchdog.region-probe-watchdog.max-suspects-per-trigger", 4)); }
    public RescueAction regionProbeActionOnNextBoot() { return RescueAction.parse(config.getString("hard-stop-watchdog.region-probe-watchdog.action-on-next-boot"), defaultAction()); }
    public boolean regionProbeForceStartupRescue() { return config.getBoolean("hard-stop-watchdog.region-probe-watchdog.force-startup-rescue", true); }
    public boolean regionProbeShutdownOnTrigger() { return config.getBoolean("hard-stop-watchdog.region-probe-watchdog.shutdown-on-trigger", true); }
    public int regionProbeRecentPlayerChunkSeconds() { return Math.max(5, config.getInt("hard-stop-watchdog.region-probe-watchdog.recent-player-chunk-seconds", 120)); }


    public boolean consoleCommandSanitizerEnabled() { return config.getBoolean("console-command-sanitizer.enabled", true); }
    public boolean consoleCommandSanitizerOnlyKnownProblematic() { return config.getBoolean("console-command-sanitizer.only-known-problematic-commands", false); }
    public boolean consoleCommandSanitizerLogRewrite() { return config.getBoolean("console-command-sanitizer.log-rewrite", true); }
    public java.util.Set<String> consoleCommandSanitizerCommands() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String value : config.getStringList("console-command-sanitizer.problematic-commands")) {
            if (value != null && !value.isBlank()) out.add(value.toLowerCase(java.util.Locale.ROOT));
        }
        if (out.isEmpty()) {
            out.add("stop");
            out.add("version");
            out.add("ver");
            out.add("about");
            out.add("plugins");
            out.add("pl");
        }
        return java.util.Collections.unmodifiableSet(out);
    }


    public boolean testToolsEnabled() { return config.getBoolean("test-tools.enabled", false); }
    public int maxDebugFreezeSeconds() { return Math.max(1, config.getInt("test-tools.max-debug-freeze-seconds", 120)); }

    public boolean stopServerIfJournalDirty() { return config.getBoolean("safety.stop-server-if-journal-dirty", true); }
    public boolean stopServerIfBackupFailed() { return config.getBoolean("safety.stop-server-if-backup-failed", true); }
    public boolean writeHumanReport() { return config.getBoolean("safety.write-human-report", true); }
}
