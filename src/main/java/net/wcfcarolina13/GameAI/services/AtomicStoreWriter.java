package net.wcfcarolina13.GameAI.services;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Replaces a store file without ever leaving it truncated or half-written.
 *
 * <p>The new content goes to a temporary sibling in the same directory, is forced to disk, and
 * only then renamed over the target. A crash or an I/O error during the write therefore leaves
 * the previous file untouched; the worst outcome is an orphaned {@code .tmp} sibling. When the
 * file system refuses an atomic rename ({@link AtomicMoveNotSupportedException}), or Windows
 * refuses it because an antivirus scanner or indexer holds the target open
 * ({@link AccessDeniedException}), a plain replacing move is tried once. The temporary file is
 * removed on every failure path and the {@link IOException} reaches the caller, which keeps its
 * own failure logging.
 *
 * <p>Pure {@code java.nio}: no Minecraft types, so it is unit-tested directly.
 */
public final class AtomicStoreWriter {
    /** Writes the new content of a store file to the stream the writer opened on the temporary file. */
    @FunctionalInterface
    public interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicStoreWriter() {}

    /** Replaces {@code target} with {@code content} encoded as UTF-8. */
    public static void writeString(Path target, String content) throws IOException {
        writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Replaces {@code target} with {@code content}. */
    public static void writeBytes(Path target, byte[] content) throws IOException {
        write(target, out -> out.write(content));
    }

    /** Replaces {@code target} with whatever {@code body} writes. */
    public static void write(Path target, Body body) throws IOException {
        writeWithBackup(target, null, body, null);
    }

    /**
     * Like {@link #write}, and once the new content is durably in the temporary file but before it
     * replaces {@code target}, copies the current {@code target} (when one exists) to
     * {@code backup}. The copy is made from an intact previous file and only when a replacement is
     * ready, so a failed write never disturbs the backup. A failure of the copy itself is passed to
     * {@code onBackupFailure} (when given) and the replacement still happens.
     */
    public static void writeWithBackup(Path target, Path backup, Body body, Consumer<IOException> onBackupFailure)
            throws IOException {
        if (target == null) throw new IOException("No target path");
        if (body == null) throw new IOException("No content to write");
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) throw new IOException("Target has no parent directory: " + target);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                // Bodies such as NbtIo.writeCompressed close the stream they are handed; the
                // wrapper keeps the channel open so it can still be forced afterwards.
                OutputStream out = new FilterOutputStream(Channels.newOutputStream(channel)) {
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };
                body.writeTo(out);
                out.flush();
                channel.force(true);
            }
            if (backup != null && Files.exists(target)) {
                try {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    if (onBackupFailure != null) onBackupFailure.accept(e);
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The move already succeeded or the original failure is being thrown.
            }
        }
    }
}
