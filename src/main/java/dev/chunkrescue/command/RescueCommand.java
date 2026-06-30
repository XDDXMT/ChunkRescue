package dev.chunkrescue.command;

import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.model.RescueAction;
import dev.chunkrescue.model.Suspect;
import dev.chunkrescue.startup.SuspectStore;
import dev.chunkrescue.watchdog.HardStopWatchdog;
import dev.chunkrescue.model.RegionKey;
import dev.chunkrescue.worldfile.WorldPathResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class RescueCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final SuspectStore suspectStore;
    private final HardStopWatchdog watchdog;

    public RescueCommand(JavaPlugin plugin, RescueConfig config, SuspectStore suspectStore, HardStopWatchdog watchdog) {
        this.plugin = plugin;
        this.config = config;
        this.suspectStore = suspectStore;
        this.watchdog = watchdog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§bChunkRescue §7v" + plugin.getDescription().getVersion());
            sender.sendMessage("§7Startup rescue: §f" + config.startupRescueEnabled());
            sender.sendMessage("§7Hard watchdog: §f" + config.hardStopEnabled() + " §7dedicated-thread=§f" + config.dedicatedThreadEnabled());
            if (watchdog != null) sender.sendMessage("§7Watchdog details: §f" + watchdog.statusLine());
            sender.sendMessage("§7Pending suspects: §f" + suspectStore.loadPending().size());
            sender.sendMessage("§7Suspects file: §f" + suspectStore.path());
            return true;
        }

        if (args[0].equalsIgnoreCase("suspect")) {
            return handleSuspect(sender, args);
        }

        if (args[0].equalsIgnoreCase("debug")) {
            return handleDebug(sender, args);
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§aChunkRescue config reloaded. Restart is still recommended for startup-rescue settings.");
            return true;
        }

        help(sender, label);
        return true;
    }

    private boolean handleSuspect(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            List<Suspect> list = suspectStore.loadPending();
            sender.sendMessage("§bPending suspects: §f" + list.size());
            for (Suspect s : list) {
                sender.sendMessage("§7- §f" + s.id() + " §7" + s.world() + " " + s.centerChunkX() + "," + s.centerChunkZ()
                    + " r=" + s.radiusChunks() + " action=" + s.action() + " reason=" + s.reason());
            }
            return true;
        }

        if (args.length >= 6 && args[1].equalsIgnoreCase("add")) {
            try {
                String world = args[2];
                int cx = Integer.parseInt(args[3]);
                int cz = Integer.parseInt(args[4]);
                int radius = Integer.parseInt(args[5]);
                RescueAction action = args.length >= 7 ? RescueAction.parse(args[6], config.defaultAction()) : config.defaultAction();
                String reason = args.length >= 8 ? String.join("_", Arrays.copyOfRange(args, 7, args.length)) : "MANUAL";
                Suspect suspect = new Suspect(
                    "manual_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8),
                    world, cx, cz, radius, reason, action, Instant.now(), List.of("addedBy=" + sender.getName())
                );
                suspectStore.add(suspect);
                sender.sendMessage("§aAdded suspect chunk. It will be handled on next unclean startup, or next startup if only-after-unclean-shutdown=false.");
                return true;
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cchunkX/chunkZ/radius must be integers.");
                return true;
            } catch (IOException ex) {
                sender.sendMessage("§cFailed to save suspect: " + ex.getMessage());
                return true;
            }
        }

        sender.sendMessage("§eUsage: /chunkrescue suspect add <world> <chunkX> <chunkZ> <radius> [action] [reason]");
        sender.sendMessage("§eUsage: /chunkrescue suspect list");
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!config.testToolsEnabled()) {
            sender.sendMessage("§cChunkRescue test-tools are disabled. Set test-tools.enabled=true on a copied test server, then restart.");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("paths")) {
            if (args.length < 5) {
                sender.sendMessage("§eUsage: /chunkrescue debug paths <world> <chunkX> <chunkZ>");
                return true;
            }
            try {
                String world = args[2];
                int cx = Integer.parseInt(args[3]);
                int cz = Integer.parseInt(args[4]);
                RegionKey key = new RegionKey(world, Math.floorDiv(cx, 32), Math.floorDiv(cz, 32));
                WorldPathResolver resolver = new WorldPathResolver(plugin);
                for (String line : resolver.diagnostics(key).split("\n")) {
                    if (!line.isBlank()) sender.sendMessage("§7" + line);
                }
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cchunkX/chunkZ must be integers.");
            }
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("worlds")) {
            WorldPathResolver resolver = new WorldPathResolver(plugin);
            sender.sendMessage("§bDetected world-like folders under §f" + resolver.worldContainer());
            for (Path path : resolver.detectedWorldLikeFolders()) sender.sendMessage("§7- §f" + path);
            return true;
        }

        if (args.length >= 2 && (args[1].equalsIgnoreCase("stale-heartbeat") || args[1].equalsIgnoreCase("freeze-global") || args[1].equalsIgnoreCase("force-hard-stop"))) {
            if (watchdog == null) {
                sender.sendMessage("§cWatchdog is not available.");
                return true;
            }
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("rescue-here")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cThis command must be executed by an in-game player. Use debug rescue-nextboot from console.");
                return true;
            }
            int radius = config.machineRadiusChunks();
            if (args.length >= 3) {
                try { radius = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { }
            }
            RescueAction action = args.length >= 4 ? RescueAction.parse(args[3], RescueAction.DELETE_CHUNKS_REGENERATE) : RescueAction.DELETE_CHUNKS_REGENERATE;
            String reason = args.length >= 5 ? String.join("_", Arrays.copyOfRange(args, 4, args.length)) : "DEBUG_RESCUE_HERE";
            try {
                int cx = player.getLocation().getChunk().getX();
                int cz = player.getLocation().getChunk().getZ();
                String world = player.getWorld().getName();
                addSuspectAndForceNextBoot(sender, world, cx, cz, radius, action, reason);
                sender.sendMessage("§eNow stop the server normally with §fstop§e, then start it again. This chunk/radius will be backed up and reset at STARTUP.");
            } catch (Exception ex) {
                sender.sendMessage("§cFailed to queue rescue-here: " + ex.getMessage());
            }
            return true;
        }

        if (args.length >= 6 && args[1].equalsIgnoreCase("rescue-nextboot")) {
            try {
                String world = args[2];
                int cx = Integer.parseInt(args[3]);
                int cz = Integer.parseInt(args[4]);
                int radius = Integer.parseInt(args[5]);
                RescueAction action = args.length >= 7 ? RescueAction.parse(args[6], RescueAction.DELETE_CHUNKS_REGENERATE) : RescueAction.DELETE_CHUNKS_REGENERATE;
                String reason = args.length >= 8 ? String.join("_", Arrays.copyOfRange(args, 7, args.length)) : "DEBUG_RESCUE_NEXTBOOT";
                addSuspectAndForceNextBoot(sender, world, cx, cz, radius, action, reason);
                sender.sendMessage("§eNow stop the server normally with §fstop§e, then start it again. The target will be backed up and reset at STARTUP.");
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cchunkX/chunkZ/radius must be integers.");
            } catch (Exception ex) {
                sender.sendMessage("§cFailed to queue debug rescue: " + ex.getMessage());
            }
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("force-hard-stop")) {
            watchdog.triggerEmergencyStop("DEBUG_FORCE_HARD_STOP", 0L);
            sender.sendMessage("§eDEBUG: force-hard-stop requested.");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("stale-heartbeat")) {
            long millis = (long) (config.noGlobalHeartbeatTimeoutSeconds() + 2) * 1000L;
            if (args.length >= 3) {
                try { millis = Long.parseLong(args[2]); } catch (NumberFormatException ignored) { }
            }
            watchdog.debugMakeHeartbeatStale(millis);
            sender.sendMessage("§eDEBUG: heartbeat set stale by " + millis + "ms. Watchdog should react on its next check.");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("freeze-global")) {
            long seconds = config.noGlobalHeartbeatTimeoutSeconds() + config.gracefulWaitSeconds() + 15L;
            if (args.length >= 3) {
                try { seconds = Long.parseLong(args[2]); } catch (NumberFormatException ignored) { }
            }
            seconds = Math.max(1L, Math.min(seconds, config.maxDebugFreezeSeconds()));
            watchdog.debugFreezeGlobalScheduler(seconds);
            sender.sendMessage("§eDEBUG: scheduled global scheduler freeze for " + seconds + "s.");
            return true;
        }

        sender.sendMessage("§eUsage: /chunkrescue debug rescue-here [radius] [action] [reason]");
        sender.sendMessage("§eUsage: /chunkrescue debug rescue-nextboot <world> <chunkX> <chunkZ> <radius> [action] [reason]");
        sender.sendMessage("§eUsage: /chunkrescue debug paths <world> <chunkX> <chunkZ>");
        sender.sendMessage("§eUsage: /chunkrescue debug worlds");
        sender.sendMessage("§eUsage: /chunkrescue debug stale-heartbeat [millisAgo]");
        sender.sendMessage("§eUsage: /chunkrescue debug freeze-global [seconds]");
        sender.sendMessage("§eUsage: /chunkrescue debug force-hard-stop");
        return true;
    }

    private void addSuspectAndForceNextBoot(CommandSender sender, String world, int cx, int cz, int radius, RescueAction action, String reason) throws IOException {
        Suspect suspect = new Suspect(
            "debug_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8),
            world, cx, cz, Math.max(0, radius), reason, action, Instant.now(),
            List.of("addedBy=" + sender.getName(), "forceStartupRescue=true")
        );
        suspectStore.add(suspect);

        Path state = plugin.getDataFolder().toPath().resolve("runtime-state");
        Files.createDirectories(state);
        String safeSender = sender.getName().replace("\\", "_").replace("\"", "'");
        String safeWorld = world.replace("\\", "_").replace("\"", "'");
        Files.writeString(state.resolve("force-startup-rescue.flag"),
            "createdAt: \"" + Instant.now() + "\"\n"
                + "reason: \"debug queued by " + safeSender + "\"\n"
                + "target: \"" + safeWorld + " " + cx + " " + cz + " r=" + Math.max(0, radius) + "\"\n",
            StandardCharsets.UTF_8
        );
        sender.sendMessage("§aQueued startup rescue: §f" + world + " " + cx + "," + cz + " r=" + Math.max(0, radius) + " action=" + action);
        sender.sendMessage("§7force-startup-rescue.flag written, so clean shutdown will still trigger the STARTUP repair once.");
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§bChunkRescue commands:");
        sender.sendMessage("§7/" + label + " status");
        sender.sendMessage("§7/" + label + " suspect add <world> <chunkX> <chunkZ> <radius> [action] [reason]");
        sender.sendMessage("§7/" + label + " suspect list");
        sender.sendMessage("§7/" + label + " debug rescue-here [radius] [action] [reason]");
        sender.sendMessage("§7/" + label + " debug rescue-nextboot <world> <chunkX> <chunkZ> <radius> [action] [reason]");
        sender.sendMessage("§7/" + label + " debug paths <world> <chunkX> <chunkZ>");
        sender.sendMessage("§7/" + label + " debug worlds");
        sender.sendMessage("§7/" + label + " debug stale-heartbeat [millisAgo]");
        sender.sendMessage("§7/" + label + " debug freeze-global [seconds]");
        sender.sendMessage("§7/" + label + " debug force-hard-stop");
        sender.sendMessage("§7/" + label + " reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("status", "suspect", "debug", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("suspect")) return filter(List.of("add", "list"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) return filter(List.of("rescue-here", "rescue-nextboot", "paths", "worlds", "stale-heartbeat", "freeze-global", "force-hard-stop"), args[1]);
        if (args.length == 7 && args[0].equalsIgnoreCase("suspect") && args[1].equalsIgnoreCase("add")) {
            return filter(Arrays.stream(RescueAction.values()).map(Enum::name).toList(), args[6]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String v : values) if (v.toLowerCase().startsWith(p)) out.add(v);
        return out;
    }
}
