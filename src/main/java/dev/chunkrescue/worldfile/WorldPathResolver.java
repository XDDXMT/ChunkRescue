package dev.chunkrescue.worldfile;

import dev.chunkrescue.model.RegionKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

public final class WorldPathResolver {
    private final JavaPlugin plugin;
    private final Path worldContainer;

    public WorldPathResolver(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worldContainer = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    public Path worldContainer() {
        return worldContainer;
    }

    /**
     * Backwards-compatible helper retained for commands/logging.
     * In Minecraft/Paper 26.1+, this is usually the save root folder, not the
     * folder that directly contains region/entities/poi.
     */
    public Optional<Path> worldFolder(String worldName) {
        if (worldName == null || worldName.isBlank()) return Optional.empty();

        Optional<Path> override = overridePath(worldName);
        if (override.isPresent()) return Optional.of(toSaveRootOrSelf(override.get()));

        Path direct = worldContainer.resolve(worldName).normalize();
        if (Files.isDirectory(direct)) return Optional.of(toSaveRootOrSelf(direct));

        Optional<Path> directCaseInsensitive = findDirectChildCaseInsensitive(worldName);
        if (directCaseInsensitive.isPresent()) return Optional.of(toSaveRootOrSelf(directCaseInsensitive.get()));

        Path levelNameRoot = worldContainer.resolve(levelName()).normalize();
        if (Files.isDirectory(levelNameRoot)) return Optional.of(levelNameRoot);

        Optional<Path> shallow = findShallowFolderByName(worldName);
        return shallow.map(this::toSaveRootOrSelf);
    }

    /**
     * Resolve the dimension data folder(s) for a Bukkit/Paper world identifier.
     *
     * Minecraft/Paper 26.1 changed default dimensions to:
     *   <save>/dimensions/minecraft/overworld/{region,entities,poi}
     *   <save>/dimensions/minecraft/the_nether/{region,entities,poi}
     *   <save>/dimensions/minecraft/the_end/{region,entities,poi}
     *
     * Older Anvil layouts are still supported as a fallback:
     *   <save>/{region,entities,poi}
     */
    public List<Path> dimensionDataFolders(String worldName) {
        if (worldName == null || worldName.isBlank()) return List.of();

        List<Path> out = new ArrayList<>();

        // 1. Explicit override wins. It may point either to a dimension data folder
        //    or to a save root folder that contains dimensions/.
        Optional<Path> override = overridePath(worldName);
        if (override.isPresent()) {
            addDataFoldersFromBase(out, override.get(), worldName, true);
            if (!out.isEmpty()) return dedupe(out);
        }

        // 2. Exact/direct world folder name, for custom worlds or old layouts.
        Path direct = worldContainer.resolve(worldName).normalize();
        if (Files.isDirectory(direct)) addDataFoldersFromBase(out, direct, worldName, true);

        Optional<Path> directCaseInsensitive = findDirectChildCaseInsensitive(worldName);
        directCaseInsensitive.ifPresent(path -> addDataFoldersFromBase(out, path, worldName, true));

        if (!out.isEmpty()) return dedupe(out);

        // 3. 26.1 default: all default dimensions are inside the main level-name save root.
        Path levelRoot = worldContainer.resolve(levelName()).normalize();
        if (Files.isDirectory(levelRoot)) addDataFoldersFromBase(out, levelRoot, worldName, false);

        if (!out.isEmpty()) return dedupe(out);

        // 4. Direct dimension key lookup under any detected save root.
        inferDimension(worldName).ifPresent(dim -> {
            for (Path root : detectedSaveRoots()) {
                Path p = root.resolve("dimensions").resolve(dim.namespace()).resolve(dim.path()).normalize();
                if (looksLikeDimensionDataFolder(p)) out.add(p);
            }
        });

        return dedupe(out);
    }

    public List<Path> existingRegionFiles(RegionKey key) {
        String fileName = key.fileName();
        List<Path> result = new ArrayList<>();
        for (Path dataFolder : dimensionDataFolders(key.world())) {
            for (RegionDataKind kind : RegionDataKind.values()) {
                Path p = dataFolder.resolve(kind.folderName()).resolve(fileName).normalize();
                if (Files.isRegularFile(p)) result.add(p);
            }
        }
        return dedupe(result).stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    public Optional<Path> existingRegionFile(RegionKey key, RegionDataKind kind) {
        String fileName = key.fileName();
        for (Path dataFolder : dimensionDataFolders(key.world())) {
            Path p = dataFolder.resolve(kind.folderName()).resolve(fileName).normalize();
            if (Files.isRegularFile(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    public Path defaultRegionFile(RegionKey key, RegionDataKind kind) {
        List<Path> folders = dimensionDataFolders(key.world());
        if (!folders.isEmpty()) {
            return folders.get(0).resolve(kind.folderName()).resolve(key.fileName()).normalize();
        }

        // If no folder currently exists, still print the most likely 26.1 path.
        Path levelRoot = worldContainer.resolve(levelName()).normalize();
        DimensionId dim = inferDimension(key.world()).orElse(new DimensionId("minecraft", "overworld"));
        return levelRoot.resolve("dimensions").resolve(dim.namespace()).resolve(dim.path())
            .resolve(kind.folderName()).resolve(key.fileName()).normalize();
    }

    public List<Path> detectedWorldLikeFolders() {
        return detectedSaveRoots();
    }

    public String diagnostics(RegionKey key) {
        StringBuilder sb = new StringBuilder();
        sb.append("worldContainer=").append(worldContainer).append('\n');
        sb.append("server.properties level-name=").append(levelName()).append('\n');
        sb.append("worldIdentifier=").append(key.world()).append('\n');
        sb.append("inferredDimension=").append(inferDimension(key.world()).map(DimensionId::asString).orElse("<unknown>")).append('\n');
        sb.append("region=").append(key.regionX()).append(',').append(key.regionZ()).append(' ')
            .append(key.fileName()).append('\n');

        String override = plugin.getConfig().getString("world-path-overrides." + key.world());
        if (override != null && !override.isBlank()) sb.append("override=").append(override).append('\n');

        Optional<Path> folder = worldFolder(key.world());
        sb.append("resolvedSaveRootOrFolder=").append(folder.map(Path::toString).orElse("<not found>")).append('\n');

        List<Path> dataFolders = dimensionDataFolders(key.world());
        sb.append("resolvedDimensionDataFolders=").append(dataFolders.size()).append('\n');
        for (Path f : dataFolders) sb.append("  - ").append(f).append('\n');

        for (RegionDataKind kind : RegionDataKind.values()) {
            sb.append(kind.name().toLowerCase(Locale.ROOT)).append("Expected=")
                .append(defaultRegionFile(key, kind)).append('\n');
        }

        List<Path> files = existingRegionFiles(key);
        sb.append("existingMcaFiles=").append(files.size()).append('\n');
        for (Path f : files) sb.append("  - ").append(f).append('\n');

        if (files.isEmpty()) {
            sb.append("detectedSaveRoots=").append('\n');
            List<Path> worlds = detectedSaveRoots();
            for (int i = 0; i < Math.min(worlds.size(), 12); i++) {
                sb.append("  - ").append(worlds.get(i)).append('\n');
            }
            if (worlds.size() > 12) sb.append("  ... ").append(worlds.size() - 12).append(" more\n");
        }
        return sb.toString();
    }

    private Optional<Path> overridePath(String worldName) {
        String override = plugin.getConfig().getString("world-path-overrides." + worldName);
        if (override == null || override.isBlank()) return Optional.empty();
        Path p = Path.of(override);
        if (!p.isAbsolute()) p = worldContainer.resolve(p);
        p = p.toAbsolutePath().normalize();
        if (Files.isDirectory(p)) return Optional.of(p);
        plugin.getLogger().warning("[ChunkRescue] world-path-overrides." + worldName + " points to a missing folder: " + p);
        return Optional.empty();
    }

    private void addDataFoldersFromBase(List<Path> out, Path base, String worldName, boolean allowOldLayout) {
        Path normalized = base.toAbsolutePath().normalize();

        if (looksLikeDimensionDataFolder(normalized)) {
            out.add(normalized);
            return;
        }

        Optional<DimensionId> dim = inferDimension(worldName);
        if (dim.isPresent()) {
            Path p = normalized.resolve("dimensions").resolve(dim.get().namespace()).resolve(dim.get().path()).normalize();
            if (looksLikeDimensionDataFolder(p)) out.add(p);
        }

        // If the identifier is a raw dimension path like "dimensions/minecraft/overworld"
        // or a panel exposes a nested folder directly, keep it safe but flexible.
        if (out.isEmpty()) {
            Path p = tryFindNamedDimensionFolder(normalized, worldName).orElse(null);
            if (p != null) out.add(p);
        }

        // Old Anvil layout fallback. Only use this when the folder itself clearly has
        // region/entities/poi. Do not scan all child dimensions blindly, or we could
        // patch overworld/nether/end together.
        if (allowOldLayout && out.isEmpty() && looksLikeOldAnvilWorldDataFolder(normalized)) {
            out.add(normalized);
        }
    }

    private Optional<Path> tryFindNamedDimensionFolder(Path saveRoot, String worldName) {
        Optional<DimensionId> dim = inferDimension(worldName);
        if (dim.isPresent()) {
            Path p = saveRoot.resolve("dimensions").resolve(dim.get().namespace()).resolve(dim.get().path()).normalize();
            if (looksLikeDimensionDataFolder(p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    private Path toSaveRootOrSelf(Path p) {
        Path normalized = p.toAbsolutePath().normalize();
        if (looksLikeDimensionDataFolder(normalized)) return normalized;
        return normalized;
    }

    private Optional<Path> findDirectChildCaseInsensitive(String worldName) {
        try (Stream<Path> stream = Files.list(worldContainer)) {
            String lower = worldName.toLowerCase(Locale.ROOT);
            return stream.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst()
                .map(p -> p.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> findShallowFolderByName(String worldName) {
        String lower = worldName.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(worldContainer, 4)) {
            return stream.filter(Files::isDirectory)
                .filter(p -> p.getFileName() != null)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(lower))
                .filter(this::looksLikeSaveRootOrDataFolder)
                .findFirst()
                .map(p -> p.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private List<Path> detectedSaveRoots() {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worldContainer, 5)) {
            stream.filter(Files::isDirectory)
                .filter(this::looksLikeSaveRootOrDataFolder)
                .sorted(Comparator.comparing(Path::toString))
                .forEach(result::add);
        } catch (IOException e) {
            plugin.getLogger().warning("[ChunkRescue] Failed to scan world container " + worldContainer + ": " + e.getMessage());
        }
        return dedupe(result);
    }

    private boolean looksLikeSaveRootOrDataFolder(Path p) {
        return Files.exists(p.resolve("level.dat"))
            || Files.isDirectory(p.resolve("dimensions"))
            || looksLikeDimensionDataFolder(p)
            || looksLikeOldAnvilWorldDataFolder(p);
    }

    private boolean looksLikeDimensionDataFolder(Path p) {
        return Files.isDirectory(p.resolve("region"))
            || Files.isDirectory(p.resolve("entities"))
            || Files.isDirectory(p.resolve("poi"));
    }

    private boolean looksLikeOldAnvilWorldDataFolder(Path p) {
        return Files.isDirectory(p.resolve("region"))
            || Files.isDirectory(p.resolve("entities"))
            || Files.isDirectory(p.resolve("poi"));
    }

    private String levelName() {
        Path props = worldContainer.resolve("server.properties");
        if (!Files.isRegularFile(props)) return "world";
        Properties p = new Properties();
        try (var in = Files.newInputStream(props)) {
            p.load(in);
            String name = p.getProperty("level-name");
            if (name != null && !name.isBlank()) return name.trim();
        } catch (IOException ignored) {
        }
        return "world";
    }

    private Optional<DimensionId> inferDimension(String worldName) {
        if (worldName == null || worldName.isBlank()) return Optional.empty();
        String raw = worldName.trim();
        String lower = raw.toLowerCase(Locale.ROOT).replace('\\', '/');

        if (lower.contains(":")) {
            String[] parts = lower.split(":", 2);
            if (!parts[0].isBlank() && !parts[1].isBlank()) {
                return Optional.of(new DimensionId(parts[0], normalizeDimPath(parts[1])));
            }
        }

        if (lower.contains("/dimensions/")) {
            String[] parts = lower.split("/dimensions/", 2);
            String tail = parts.length == 2 ? parts[1] : lower;
            String[] dimParts = tail.split("/", 2);
            if (dimParts.length == 2 && !dimParts[0].isBlank() && !dimParts[1].isBlank()) {
                return Optional.of(new DimensionId(dimParts[0], normalizeDimPath(dimParts[1])));
            }
        }

        String level = levelName().toLowerCase(Locale.ROOT);
        if (lower.equals(level) || lower.equals("world") || lower.equals("overworld") || lower.endsWith("_overworld")) {
            return Optional.of(new DimensionId("minecraft", "overworld"));
        }
        if (lower.equals("the_nether") || lower.equals("nether") || lower.endsWith("_nether") || lower.endsWith("-nether") || lower.contains("the_nether")) {
            return Optional.of(new DimensionId("minecraft", "the_nether"));
        }
        if (lower.equals("the_end") || lower.equals("end") || lower.endsWith("_the_end") || lower.endsWith("_end") || lower.endsWith("-end") || lower.contains("the_end")) {
            return Optional.of(new DimensionId("minecraft", "the_end"));
        }
        return Optional.empty();
    }

    private String normalizeDimPath(String path) {
        return path.replace(':', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private <T> List<T> dedupe(List<T> in) {
        Set<T> seen = new LinkedHashSet<>();
        List<T> out = new ArrayList<>();
        for (T p : in) {
            if (seen.add(p)) out.add(p);
        }
        return out;
    }

    private record DimensionId(String namespace, String path) {
        String asString() { return namespace + ":" + path; }
    }
}
