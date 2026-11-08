package net.wcfcarolina13.GameAI.services;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner-only (0600) file mode for per-save store files that hold player UUIDs and owner data.
 *
 * <p>The fingerprinted registries (furnaces, beehives, crafting/hunt history, hunt settings,
 * fortifications, villages, bot world state) already get 0600 on POSIX: they write through
 * {@code Files.createTempFile}, which the JDK creates {@code rw-------}, and the atomic move keeps
 * that mode. The soul store writes its temp files by name, so they would land at the umask
 * default (usually 0644); it calls {@link #restrictToOwner} before each move and when it creates
 * a transcript. The supply "Always" grants ({@code SupplyRequestService}) and the group-chat
 * hint receipts ({@code GroupChatHint}) name their temp files the same way and do the same.
 *
 * <p>Never fails a save: on a non-POSIX file system (Windows) nothing is done, and any error is
 * logged at DEBUG and ignored. Hand-edited config files are out of scope.
 */
public final class StoreFileModePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.store-mode");

    /** The mode applied to store files: owner read/write, nothing for group or others. */
    public static final String OWNER_ONLY = "rw-------";

    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString(OWNER_ONLY);

    private StoreFileModePolicy() {
    }

    /** Pure decision: restrict only where the file system has a POSIX attribute view. */
    public static boolean shouldRestrict(Set<String> supportedFileAttributeViews) {
        return supportedFileAttributeViews != null && supportedFileAttributeViews.contains("posix");
    }

    /** The permission set applied (a fresh copy each call). */
    public static Set<PosixFilePermission> ownerOnlyPermissions() {
        return PosixFilePermissions.fromString(OWNER_ONLY);
    }

    /**
     * Sets {@code file} to owner read/write where supported. Returns whether the mode was set;
     * false on a non-POSIX file system or any failure, which is logged at DEBUG only.
     */
    public static boolean restrictToOwner(Path file) {
        if (file == null) {
            return false;
        }
        try {
            if (!shouldRestrict(file.getFileSystem().supportedFileAttributeViews())) {
                return false;
            }
            Files.setPosixFilePermissions(file, OWNER_ONLY_PERMISSIONS);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Could not set owner-only mode on {}: {}", file, e.toString());
            return false;
        }
    }

    /** True when the default file system supports POSIX modes (used by tests to skip on Windows). */
    public static boolean defaultFileSystemIsPosix() {
        return shouldRestrict(FileSystems.getDefault().supportedFileAttributeViews());
    }
}
