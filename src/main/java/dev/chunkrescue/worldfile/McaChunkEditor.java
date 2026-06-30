package dev.chunkrescue.worldfile;

import dev.chunkrescue.model.ChunkPos;
import dev.chunkrescue.util.SafeFiles;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

public final class McaChunkEditor {
    private static final int MCA_HEADER_BYTES = 8192;
    private static final int LOCATION_TABLE_BYTES = 4096;

    public int deleteChunkRecords(Path mcaFile, Collection<ChunkPos> chunks) throws IOException {
        if (mcaFile == null || !Files.exists(mcaFile) || chunks == null || chunks.isEmpty()) return 0;
        if (Files.size(mcaFile) < MCA_HEADER_BYTES) {
            throw new IOException("Invalid MCA file, header is too small: " + mcaFile);
        }

        Path tmp = mcaFile.resolveSibling(mcaFile.getFileName() + ".chunkrescue.tmp");
        Files.copy(mcaFile, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

        int changed = 0;
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            for (ChunkPos chunk : chunks) {
                int index = chunk.localMcaIndex();
                long locationOffset = index * 4L;
                raf.seek(locationOffset);
                int oldLocation = raf.readInt();
                raf.seek(locationOffset);
                raf.writeInt(0);

                raf.seek(LOCATION_TABLE_BYTES + locationOffset);
                raf.writeInt(0);
                if (oldLocation != 0) changed++;
            }
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }

        SafeFiles.moveReplace(tmp, mcaFile);
        return changed;
    }

    public boolean deleteWholeRegionFile(Path mcaFile) throws IOException {
        if (mcaFile == null || !Files.exists(mcaFile)) return false;
        Files.delete(mcaFile);
        return true;
    }
}
