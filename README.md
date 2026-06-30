# ChunkRescue

ChunkRescue is a Folia-compatible startup rescue plugin for technical Minecraft servers.

It is designed for incidents where a redstone/slimestone machine, TNT minecart setup, falling-block chain, or entity generator makes a server repeatedly freeze as soon as the chunk loads.

## Target platform

- Folia 26.1.2 public API
- Folia downstreams that preserve Paper/Folia public API, including Lophine/Luminol-style branches
- Java 25 toolchain, matching current Paper 26.1.2 project setup

The plugin intentionally avoids Lophine-private APIs so the project can stay open-source and portable.

## MVP features

- `load: STARTUP`
- `folia-supported: true`
- Runtime suspect chunk recorder
- Dedicated JVM watchdog thread, configurable on/off
- Global Folia heartbeat
- 30-second hard-stop watchdog
- Startup rescue after unclean shutdown
- Mandatory backup before destructive operation
- SHA-256 backup verification
- MCA header-level chunk deletion
- Delete target chunk records from:
  - `region/r.x.z.mca`
  - `entities/r.x.z.mca`
  - `poi/r.x.z.mca`
- Optional whole-region entity purge
- Optional whole-region regenerate

## Important limitation

This MVP supports **Anvil `.mca`** storage only.

Some Luminol/Lophine builds can support additional storage formats such as `linear` or `b_linear`. ChunkRescue deliberately fails closed for those in this MVP. Keep `startup-rescue.storage-format: ANVIL_MCA_ONLY` unless you add a storage backend.

## Why MCA header deletion?

An MCA file contains 1024 chunk slots. To make a chunk regenerate, this MVP zeros the target chunk's location and timestamp table entries. The old sectors become unreachable and the server treats that chunk as missing. On next load, the chunk is generated again.

This avoids parsing and rewriting full chunk NBT, which is safer for an emergency MVP.

## Build

```bash
./gradlew build
```

Output:

```text
build/libs/ChunkRescue-0.1.2-mvp.jar
```

## Install

1. Stop the server.
2. Put the jar in `plugins/`.
3. Start once to generate config.
4. Tune `plugins/ChunkRescue/config.yml`.
5. Restart.

## Commands

```text
/chunkrescue status
/chunkrescue suspect list
/chunkrescue suspect add <world> <chunkX> <chunkZ> <radius> [action] [reason]
/chunkrescue reload
```

Actions implemented in MVP:

```text
DELETE_CHUNKS_REGENERATE
PURGE_REGION_ENTITIES
DELETE_REGION_REGENERATE
STOP_SERVER
```

Reserved for later:

```text
PURGE_ENTITIES_ONLY
DISABLE_MACHINE_BLOCKS
SAFE_VOID_CHUNK
```

## Default rescue chain

When a chunk is marked as suspicious and the server later has an unclean shutdown, the next startup performs:

1. Read `plugins/ChunkRescue/suspects.yml`.
2. Refuse if the world is already loaded.
3. Backup affected `.mca` files.
4. Verify backup hash.
5. Delete target chunk records from region/entities/poi MCA headers.
6. Write a repair journal and human report.
7. Continue startup.

If backup fails, it refuses to patch.

## Watchdog behavior

`dedicated-watchdog-thread.enabled: true` creates a plugin-owned JVM thread. It is **not** a Folia region tick thread.

That thread only checks heartbeat and writes plugin files. It never touches world/chunk/entity objects.

On missing global heartbeat for 30 seconds, it:

1. Flushes suspect records.
2. Writes `crash-marker.yml`.
3. Writes a thread dump.
4. Calls `Bukkit.shutdown()`.
5. Waits `graceful-wait-seconds`.
6. Calls `Runtime.halt(137)` if still alive.

## Open-source notes

The project is intentionally small and dependency-light. The emergency destructive path does not depend on NMS, Mojang mappings, Lophine internals, or external NBT libraries.

A future precise-machine-kill mode should add a real chunk NBT patcher and separate storage backends instead of mixing those concerns into the MCA header editor.


## Java 25 / Gradle toolchain

This project compiles with a Java 25 toolchain because the target 26.1.2 API line uses the current platform Java level.

If JDK 25 is not installed, Gradle will try to download it automatically through the Foojay toolchain resolver configured in `settings.gradle.kts`. If your network blocks that, install a local JDK 25 and run:

```powershell
gradle --stop
gradle clean build --refresh-dependencies
```

On Windows you can install Temurin JDK 25 with:

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```
