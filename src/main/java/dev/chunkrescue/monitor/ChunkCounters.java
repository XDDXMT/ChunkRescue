package dev.chunkrescue.monitor;

import java.util.concurrent.atomic.LongAdder;

public final class ChunkCounters {
    public final LongAdder entitySpawns = new LongAdder();
    public final LongAdder tntMinecartSpawns = new LongAdder();
    public final LongAdder fallingBlockSpawns = new LongAdder();
    public final LongAdder itemSpawns = new LongAdder();
    public final LongAdder pistonEvents = new LongAdder();
    public final LongAdder redstoneEvents = new LongAdder();
    public final LongAdder dispenserEvents = new LongAdder();
    public final LongAdder explosionEvents = new LongAdder();

    public Snapshot snapshotThenReset() {
        return new Snapshot(
            entitySpawns.sumThenReset(),
            tntMinecartSpawns.sumThenReset(),
            fallingBlockSpawns.sumThenReset(),
            itemSpawns.sumThenReset(),
            pistonEvents.sumThenReset(),
            redstoneEvents.sumThenReset(),
            dispenserEvents.sumThenReset(),
            explosionEvents.sumThenReset()
        );
    }

    public record Snapshot(long entitySpawns, long tntMinecartSpawns, long fallingBlockSpawns, long itemSpawns,
                           long pistonEvents, long redstoneEvents, long dispenserEvents, long explosionEvents) {}
}
