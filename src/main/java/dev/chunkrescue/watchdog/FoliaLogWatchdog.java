package dev.chunkrescue.watchdog;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.ChunkKey;
import dev.chunkrescue.monitor.RuntimeMonitor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FoliaLogWatchdog {
    private static final Pattern FOLIA_REGION_REPORT = Pattern.compile(
        "Tick region located in world '([^']+)' around chunk '\\[(-?\\d+),\\s*(-?\\d+)\\]' has not responded in ([0-9.]+)s"
    );

    private static final Pattern HEALTH_REGION_HEADER = Pattern.compile(
        "Region at ([^ ]+) \\((-?\\d+),\\s*(-?\\d+)\\):"
    );

    private static final Pattern HEALTH_REGION_METRICS = Pattern.compile(
        "([0-9.]+)% util \\| ([0-9,.]+) mspt \\| ([0-9.]+) TPS"
    );

    private static final Pattern HEALTH_REGION_COUNTS = Pattern.compile(
        "Chunks:\\s*([0-9,]+)\\s+Players:\\s*([0-9,]+)\\s+Entities:\\s*([0-9,]+)"
    );

    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final RuntimeMonitor runtimeMonitor;
    private final HardStopWatchdog parent;
    private final Path latestLog;

    private long offset = 0L;
    private final ArrayDeque<Report> recentReports = new ArrayDeque<>();
    private final Map<ChunkKey, Integer> consecutiveReports = new HashMap<>();
    private final Map<ChunkKey, Long> lastMarkedMillis = new HashMap<>();
    private HealthRegion pendingHealthRegion;

    public FoliaLogWatchdog(JavaPlugin plugin, RescueConfig config, RuntimeMonitor runtimeMonitor, HardStopWatchdog parent) {
        this.plugin = plugin;
        this.config = config;
        this.runtimeMonitor = runtimeMonitor;
        this.parent = parent;
        Path configured = Path.of(config.regionLogWatchdogPath());
        this.latestLog = configured.isAbsolute() ? configured : plugin.getServer().getWorldContainer().toPath().resolve(configured).normalize();
    }

    public void check() {
        if (!config.regionLogWatchdogEnabled() && !config.healthReportWatchdogEnabled()) return;
        try {
            readNewLines();
        } catch (Throwable t) {
            plugin.getLogger().warning("[ChunkRescue] Region log watchdog read failed: " + t.getMessage());
        }
    }

    public String status() {
        return "region-log=" + config.regionLogWatchdogEnabled()
            + ", health-report=" + config.healthReportWatchdogEnabled()
            + ", latestLog=" + latestLog;
    }

    private void readNewLines() throws IOException {
        if (!Files.exists(latestLog)) return;
        long length = Files.size(latestLog);
        if (length < offset) offset = 0L; // rotated/truncated
        if (length == offset) return;
        long maxRead = 2L * 1024L * 1024L;
        if (length - offset > maxRead) offset = Math.max(0L, length - maxRead);

        try (RandomAccessFile raf = new RandomAccessFile(latestLog.toFile(), "r")) {
            raf.seek(offset);
            String line;
            while ((line = raf.readLine()) != null) {
                parseLine(line);
            }
            offset = raf.getFilePointer();
        }
    }

    private void parseLine(String line) {
        if (config.regionLogWatchdogEnabled()) {
            Matcher m = FOLIA_REGION_REPORT.matcher(line);
            if (m.find()) {
                String world = m.group(1);
                int cx = Integer.parseInt(m.group(2));
                int cz = Integer.parseInt(m.group(3));
                double reportedSeconds = Double.parseDouble(m.group(4));
                handleFoliaRegionReport(world, cx, cz, reportedSeconds, line);
                return;
            }
        }

        if (!config.healthReportWatchdogEnabled()) return;

        Matcher header = HEALTH_REGION_HEADER.matcher(line);
        if (header.find()) {
            String world = header.group(1);
            int x = Integer.parseInt(header.group(2));
            int z = Integer.parseInt(header.group(3));
            int cx;
            int cz;
            String mode = config.healthReportCoordinateMode().toUpperCase(Locale.ROOT);
            if ("CHUNK".equals(mode)) {
                cx = x;
                cz = z;
            } else {
                cx = Math.floorDiv(x, 16);
                cz = Math.floorDiv(z, 16);
            }
            pendingHealthRegion = new HealthRegion(world, cx, cz, line);
            return;
        }

        Matcher metrics = HEALTH_REGION_METRICS.matcher(line);
        if (metrics.find() && pendingHealthRegion != null) {
            pendingHealthRegion.util = Double.parseDouble(metrics.group(1));
            pendingHealthRegion.mspt = parseDouble(metrics.group(2));
            pendingHealthRegion.tps = Double.parseDouble(metrics.group(3));
            pendingHealthRegion.metricsLine = line;
            return;
        }

        Matcher counts = HEALTH_REGION_COUNTS.matcher(line);
        if (counts.find() && pendingHealthRegion != null) {
            pendingHealthRegion.chunks = parseInt(counts.group(1));
            pendingHealthRegion.players = parseInt(counts.group(2));
            pendingHealthRegion.entities = parseInt(counts.group(3));
            pendingHealthRegion.countsLine = line;
            evaluateHealthRegion(pendingHealthRegion);
            pendingHealthRegion = null;
        }
    }

    private void handleFoliaRegionReport(String world, int cx, int cz, double reportedSeconds, String line) {
        if (reportedSeconds < config.regionLogMinReportedSeconds()) return;
        long now = System.currentTimeMillis();
        ChunkKey key = new ChunkKey(world, cx, cz);
        recentReports.addLast(new Report(key, now, reportedSeconds, line));
        trimRecent(now);

        int consecutive = consecutiveReports.getOrDefault(key, 0) + 1;
        consecutiveReports.clear();
        consecutiveReports.put(key, consecutive);

        int total = recentReports.size();
        plugin.getLogger().warning("[ChunkRescue] Folia region watchdog report captured: " + key.compact()
            + " reports=" + consecutive + "/" + config.regionLogConsecutiveReportsToTrigger()
            + " totalRecent=" + total + "/" + config.regionLogTotalReportsToTrigger()
            + " reported=" + reportedSeconds + "s");

        if (consecutive >= config.regionLogConsecutiveReportsToTrigger() || total >= config.regionLogTotalReportsToTrigger()) {
            List<ChunkKey> targets = topRecentReportKeys(config.regionLogMaxSuspectsPerTrigger());
            markTargetsAndMaybeStop("FOLIA_REGION_WATCHDOG", targets, config.regionLogSuspectRadiusChunks(),
                config.regionLogActionOnNextBoot(), config.regionLogForceStartupRescue(), config.regionLogShutdownOnTrigger(),
                List.of("foliaWatchdogReportedSeconds=" + reportedSeconds, "line=" + simplify(line)));
        }
    }

    private void evaluateHealthRegion(HealthRegion h) {
        boolean lowTps = h.tps <= config.healthReportLowestTpsToTrigger();
        boolean highMspt = h.mspt >= config.healthReportMsptToTrigger();
        boolean entityStorm = h.entities >= config.healthReportEntityCountToTrigger() && h.tps <= Math.max(2.0, config.healthReportLowestTpsToTrigger() * 2.0);
        if (!lowTps && !highMspt && !entityStorm) return;

        ChunkKey key = new ChunkKey(h.world, h.cx, h.cz);
        List<String> evidence = new ArrayList<>();
        evidence.add("healthReport=true");
        evidence.add("util=" + h.util);
        evidence.add("mspt=" + h.mspt);
        evidence.add("tps=" + h.tps);
        evidence.add("chunks=" + h.chunks);
        evidence.add("players=" + h.players);
        evidence.add("entities=" + h.entities);
        evidence.add("header=" + simplify(h.headerLine));
        evidence.add("metrics=" + simplify(h.metricsLine));
        evidence.add("counts=" + simplify(h.countsLine));

        markTargetsAndMaybeStop("FOLIA_HEALTH_REPORT_LOW_REGION_TPS", List.of(key), config.healthReportSuspectRadiusChunks(),
            config.healthReportActionOnNextBoot(), config.healthReportForceStartupRescue(), config.healthReportShutdownOnTrigger(), evidence);
    }

    private void markTargetsAndMaybeStop(String reason, List<ChunkKey> targets, int radius, dev.chunkrescue.model.RescueAction action,
                                         boolean forceStartupRescue, boolean shutdownOnTrigger, List<String> baseEvidence) {
        if (targets.isEmpty()) return;
        int marked = 0;
        List<String> targetTexts = new ArrayList<>();
        for (ChunkKey key : targets) {
            if (!shouldMark(key)) continue;
            List<String> evidence = new ArrayList<>(baseEvidence);
            evidence.add("detector=log-watchdog");
            try {
                runtimeMonitor.markSuspect("logwd", key.world(), key.chunkX(), key.chunkZ(), radius, reason, action, evidence);
                lastMarkedMillis.put(key, System.currentTimeMillis());
                targetTexts.add(key.compact() + " r=" + radius);
                marked++;
            } catch (IOException e) {
                plugin.getLogger().warning("[ChunkRescue] Failed to save log-watchdog suspect " + key.compact() + ": " + e.getMessage());
            }
        }
        if (marked == 0) return;

        if (forceStartupRescue) {
            try {
                WatchdogFiles.writeForceStartupRescueFlag(plugin, reason, String.join(",", targetTexts));
            } catch (IOException e) {
                plugin.getLogger().warning("[ChunkRescue] Failed to write force-startup-rescue.flag: " + e.getMessage());
            }
        }

        if (shutdownOnTrigger) {
            parent.triggerEmergencyStop(reason, 0L);
        }
    }

    private boolean shouldMark(ChunkKey key) {
        long now = System.currentTimeMillis();
        Long last = lastMarkedMillis.get(key);
        return last == null || now - last >= 120_000L;
    }

    private void trimRecent(long now) {
        long windowMs = config.regionLogWindowSeconds() * 1000L;
        while (!recentReports.isEmpty() && now - recentReports.peekFirst().timeMillis > windowMs) {
            recentReports.removeFirst();
        }
    }

    private List<ChunkKey> topRecentReportKeys(int max) {
        Map<ChunkKey, Integer> counts = new HashMap<>();
        for (Report r : recentReports) counts.merge(r.key, 1, Integer::sum);
        return counts.entrySet().stream()
            .sorted(Map.Entry.<ChunkKey, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(Math.max(1, max))
            .map(Map.Entry::getKey)
            .toList();
    }

    private int parseInt(String s) {
        return Integer.parseInt(s.replace(",", ""));
    }

    private double parseDouble(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private String simplify(String line) {
        if (line == null) return "";
        return line.replace('"', '\'').trim();
    }

    private record Report(ChunkKey key, long timeMillis, double reportedSeconds, String line) { }

    private static final class HealthRegion {
        final String world;
        final int cx;
        final int cz;
        final String headerLine;
        String metricsLine;
        String countsLine;
        double util;
        double mspt;
        double tps;
        int chunks;
        int players;
        int entities;

        HealthRegion(String world, int cx, int cz, String headerLine) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.headerLine = headerLine;
        }
    }
}
