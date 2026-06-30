package dev.chunkrescue.command;

import dev.chunkrescue.config.RescueConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Some Folia/Folia-downstream builds can throw a CommandSourceStack#getLevel() NPE
 * when slash-prefixed commands are sent through the server console.
 *
 * The dedicated server console should receive commands without a leading slash,
 * so this listener normalizes console input before Brigadier handles it.
 */
public final class ConsoleCommandSanitizer implements Listener {
    private final JavaPlugin plugin;
    private final RescueConfig config;
    private final Set<String> warnedCommands = ConcurrentHashMap.newKeySet();

    public ConsoleCommandSanitizer(JavaPlugin plugin, RescueConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (!config.consoleCommandSanitizerEnabled()) return;

        String original = event.getCommand();
        if (original == null) return;

        String trimmed = original.stripLeading();
        if (!trimmed.startsWith("/")) return;

        String stripped = stripLeadingSlashes(trimmed);
        if (stripped.isBlank()) return;

        String root = rootCommand(stripped);
        if (config.consoleCommandSanitizerOnlyKnownProblematic()
            && !config.consoleCommandSanitizerCommands().contains(root)) {
            return;
        }

        event.setCommand(stripped);
        if (config.consoleCommandSanitizerLogRewrite() && warnedCommands.add(root)) {
            plugin.getLogger().warning("[ChunkRescue] Normalized console command '/" + root + "' -> '" + root
                + "'. Console commands should not include '/', and this avoids a Folia/Lophine command-source NPE.");
        }
    }

    private static String stripLeadingSlashes(String command) {
        int i = 0;
        while (i < command.length() && command.charAt(i) == '/') i++;
        return command.substring(i);
    }

    private static String rootCommand(String command) {
        String first = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (first.startsWith("minecraft:")) first = first.substring("minecraft:".length());
        if (first.startsWith("bukkit:")) first = first.substring("bukkit:".length());
        return first;
    }
}
