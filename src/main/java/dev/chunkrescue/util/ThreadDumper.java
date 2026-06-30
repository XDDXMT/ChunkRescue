package dev.chunkrescue.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class ThreadDumper {
    private ThreadDumper() {}

    public static void writeThreadDump(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        StringBuilder sb = new StringBuilder(1024 * 64);
        sb.append("ChunkRescue thread dump at ").append(Instant.now()).append('\n');
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            sb.append('\n').append('"').append(info.getThreadName()).append('"')
              .append(" Id=").append(info.getThreadId())
              .append(' ').append(info.getThreadState()).append('\n');
            for (StackTraceElement element : info.getStackTrace()) {
                sb.append("    at ").append(element).append('\n');
            }
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }
}
