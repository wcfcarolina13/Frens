package net.wcfcarolina13.GameAI.services;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Moves a store file that could not be parsed out of the way, so the store can start empty and
 * save normally instead of silently dropping every write for the rest of the session.
 *
 * <p>The original is never overwritten or deleted: it is renamed to
 * {@code <name>.bad-<yyyyMMdd-HHmmss>} (then {@code -1}, {@code -2}, ... when that name is
 * taken), and when no rename succeeds the caller keeps refusing to save.
 */
public final class UnreadableStoreFile {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Suffixes tried after the plain timestamp before giving up. */
    static final int MAX_SUFFIX = 99;

    /**
     * One store file that could not be read.
     *
     * @param backupName file name of the {@code .bad-} copy, or {@code null} when the file was left
     *                   in place and the store refuses to save
     */
    public record Problem(String label, String backupName) {}

    /**
     * Problems seen this game session, for the admin join notice. Stores load once per game
     * process (their {@code loaded} flags are never reset), so the list is not cleared at server
     * start: a store that refused to save keeps refusing across world reloads.
     */
    private static final CopyOnWriteArrayList<Problem> PROBLEMS = new CopyOnWriteArrayList<>();

    private UnreadableStoreFile() {}

    /** Admin lines for every store file that could not be read this session. */
    public static List<String> adminNotices() {
        return formatNotices(PROBLEMS);
    }

    /** Plain admin lines for {@code problems}, one per problem, in order. */
    public static List<String> formatNotices(List<Problem> problems) {
        List<String> lines = new ArrayList<>();
        if (problems == null) return lines;
        for (Problem problem : problems) {
            if (problem == null) continue;
            String label = problem.label() == null || problem.label().isBlank() ? "store" : problem.label();
            lines.add(problem.backupName() != null
                    ? "Frens could not read the " + label + " file. It was moved to " + problem.backupName()
                            + " and starts empty this session."
                    : "Frens could not read the " + label + " file. It was left in place and changes will not be saved this session.");
        }
        return lines;
    }

    static List<Problem> problems() {
        return List.copyOf(PROBLEMS);
    }

    static void clearProblems() {
        PROBLEMS.clear();
    }

    private static void record(String label, Path backup) {
        Path name = backup == null ? null : backup.getFileName();
        Problem problem = new Problem(label, name == null ? null : name.toString());
        PROBLEMS.addIfAbsent(problem);
    }

    /**
     * Quarantines {@code file} only when {@code failure} says its contents are malformed. A read
     * error (locked file, permission blip) leaves a possibly valid file in place and returns
     * {@code null}, so the caller keeps refusing to save rather than replacing it with an empty store.
     */
    public static Path quarantineIfCorrupt(Path file, Throwable failure, LocalDateTime now, Logger logger, String label) {
        if (!isCorruptContent(failure)) {
            if (logger != null) {
                logger.warn("Could not read the {} file ({}). It was left in place and changes to the {} will not be saved this session.",
                        label, failure == null ? "unknown error" : failure.getMessage(), label);
            }
            record(label, null);
            return null;
        }
        return quarantine(file, now, logger, label);
    }

    /** True when the outermost recognised cause is a parse error rather than an I/O error. */
    static boolean isCorruptContent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof com.google.gson.JsonIOException || t instanceof IOException
                    && !(t instanceof com.google.gson.stream.MalformedJsonException)) {
                return false;
            }
            if (t instanceof com.google.gson.JsonSyntaxException
                    || t instanceof com.google.gson.stream.MalformedJsonException
                    || t instanceof IllegalStateException
                    || t instanceof NumberFormatException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Renames {@code file} to a free {@code .bad-<timestamp>} sibling.
     *
     * @return the backup path, or {@code null} when the file could not be moved (the caller must
     *         then keep refusing to save, so the unreadable original is never overwritten)
     */
    public static Path quarantine(Path file, LocalDateTime now, Logger logger, String label) {
        if (file == null || now == null) {
            return null;
        }
        String base = file.getFileName() + ".bad-" + STAMP.format(now);
        for (int attempt = 0; attempt <= MAX_SUFFIX; attempt++) {
            Path backup = file.resolveSibling(attempt == 0 ? base : base + "-" + attempt);
            try {
                Files.move(file, backup);
                if (logger != null) {
                    logger.warn("Could not read the {} file. It was moved to {} and the {} starts empty this session.",
                            label, backup, label);
                }
                record(label, backup);
                return backup;
            } catch (FileAlreadyExistsException taken) {
                // try the next suffix
            } catch (IOException | RuntimeException e) {
                if (logger != null) {
                    logger.warn("Could not read the {} file and could not move it aside ({}). Changes to the {} will not be saved this session.",
                            label, e.getMessage(), label);
                }
                record(label, null);
                return null;
            }
        }
        if (logger != null) {
            logger.warn("Could not read the {} file and found no free backup name. Changes to the {} will not be saved this session.",
                    label, label);
        }
        record(label, null);
        return null;
    }
}
