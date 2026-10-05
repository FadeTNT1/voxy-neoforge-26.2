package me.cortex.voxy.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes complete UTF-8 files without ever truncating the previous file. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void writeString(Path file, String contents) throws IOException {
        var target = file.toAbsolutePath();
        if (Files.isSymbolicLink(target) || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Refusing to replace non-regular config file: " + target);
        }
        Files.createDirectories(target.getParent());
        var temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // A non-atomic fallback can destroy the original on failure. Refuse it.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
