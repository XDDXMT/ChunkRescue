package dev.chunkrescue;

import dev.chunkrescue.command.RescueCommand;
import dev.chunkrescue.command.ConsoleCommandSanitizer;
import dev.chunkrescue.config.RescueConfig;
import dev.chunkrescue.monitor.RuntimeMonitor;
import dev.chunkrescue.startup.StartupRescueService;
import dev.chunkrescue.startup.SuspectStore;
import dev.chunkrescue.watchdog.HardStopWatchdog;
import dev.chunkrescue.util.PlatformGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkRescuePlugin extends JavaPlugin {
    private RescueConfig rescueConfig;
    private SuspectStore suspectStore;
    private RuntimeMonitor runtimeMonitor;
    private HardStopWatchdog watchdog;
    private boolean platformAccepted;

    @Override
    public void onLoad() {
        platformAccepted = PlatformGuard.isFoliaLike();
        if (!platformAccepted) {
            getLogger().severe("[ChunkRescue] This plugin is intentionally Folia/Folia-downstream only. Non-Folia server detected; startup rescue skipped.");
            return;
        }
        saveDefaultConfig();
        saveResourceIfMissing("danger-blocks.yml");
        this.rescueConfig = new RescueConfig(getConfig());
        this.suspectStore = new SuspectStore(this, rescueConfig);

        try {
            Files.createDirectories(runtimeState());
        } catch (IOException e) {
            getLogger().severe("[ChunkRescue] Failed to create runtime-state folder: " + e.getMessage());
        }

        new StartupRescueService(this, rescueConfig, suspectStore).runStartupRescueIfNeeded();
    }

    @Override
    public void onEnable() {
        if (!platformAccepted) {
            getLogger().severe("[ChunkRescue] Disabling on non-Folia platform.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        markServerRunning();
        getServer().getPluginManager().registerEvents(new ConsoleCommandSanitizer(this, rescueConfig), this);

        runtimeMonitor = new RuntimeMonitor(this, rescueConfig, suspectStore);
        runtimeMonitor.start();

        watchdog = new HardStopWatchdog(this, rescueConfig, runtimeMonitor);
        watchdog.start();

        RescueCommand rescueCommand = new RescueCommand(this, rescueConfig, suspectStore, watchdog);
        PluginCommand cmd = getCommand("chunkrescue");
        if (cmd != null) {
            cmd.setExecutor(rescueCommand);
            cmd.setTabCompleter(rescueCommand);
        }
        getLogger().info("[ChunkRescue] Enabled for Folia/Folia downstreams.");
    }

    @Override
    public void onDisable() {
        if (!platformAccepted) return;
        if (watchdog != null) watchdog.stop();
        if (runtimeMonitor != null) runtimeMonitor.stop();
        markServerCleanStopped();
    }

    private void saveResourceIfMissing(String name) {
        if (!Files.exists(getDataFolder().toPath().resolve(name))) {
            saveResource(name, false);
        }
    }

    private void markServerRunning() {
        try {
            Files.createDirectories(runtimeState());
            Files.writeString(runtimeState().resolve("server-running.flag"), "running\n");
        } catch (IOException e) {
            getLogger().warning("[ChunkRescue] Failed to write running flag: " + e.getMessage());
        }
    }

    private void markServerCleanStopped() {
        try {
            Files.deleteIfExists(runtimeState().resolve("server-running.flag"));
            Files.writeString(runtimeState().resolve("last-clean-shutdown.txt"), java.time.Instant.now().toString());
        } catch (IOException e) {
            getLogger().warning("[ChunkRescue] Failed to write clean shutdown marker: " + e.getMessage());
        }
    }

    private Path runtimeState() {
        return getDataFolder().toPath().resolve("runtime-state");
    }
}
