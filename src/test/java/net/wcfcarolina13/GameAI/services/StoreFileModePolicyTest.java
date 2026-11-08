package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFileModePolicyTest {

    @TempDir
    Path dir;

    @Test
    void restrictsOnlyWherePosixIsSupported() {
        assertTrue(StoreFileModePolicy.shouldRestrict(Set.of("basic", "owner", "unix", "posix")));
        assertFalse(StoreFileModePolicy.shouldRestrict(Set.of("basic", "owner", "dos", "acl", "user")),
                "Windows (NTFS) has no posix view: leave the mode alone");
        assertFalse(StoreFileModePolicy.shouldRestrict(Set.of()));
        assertFalse(StoreFileModePolicy.shouldRestrict(null));
    }

    @Test
    void ownerOnlyIsOwnerReadWrite() {
        assertEquals("rw-------", StoreFileModePolicy.OWNER_ONLY);
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                StoreFileModePolicy.ownerOnlyPermissions());
    }

    @Test
    void restrictToOwnerNeverThrows() {
        assertFalse(StoreFileModePolicy.restrictToOwner(null));
        assertFalse(StoreFileModePolicy.restrictToOwner(dir.resolve("missing.json")),
                "a missing file is a logged no-op, not a failed save");
    }

    @Test
    void restrictToOwnerSetsOwnerOnlyOnPosix() throws Exception {
        Assumptions.assumeTrue(StoreFileModePolicy.defaultFileSystemIsPosix(), "POSIX file system only");
        Path file = dir.resolve("store.json");
        Files.writeString(file, "{}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertTrue(StoreFileModePolicy.restrictToOwner(file));
        assertEquals(StoreFileModePolicy.ownerOnlyPermissions(), Files.getPosixFilePermissions(file));
    }

    /**
     * Pins the JDK behaviour the fingerprinted registries rely on: a {@code createTempFile} temp
     * moved over a 0644 file leaves the target owner-only, so they need no explicit call.
     */
    @Test
    void createTempFileThenAtomicMoveIsOwnerOnlyOnPosix() throws Exception {
        Assumptions.assumeTrue(StoreFileModePolicy.defaultFileSystemIsPosix(), "POSIX file system only");
        Path file = dir.resolve("furnace-registry.json");
        Files.writeString(file, "{}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        Path temporary = Files.createTempFile(dir, "furnace-registry-", ".tmp");
        Files.writeString(temporary, "{\"a\":1}");
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }

        assertEquals(StoreFileModePolicy.ownerOnlyPermissions(), Files.getPosixFilePermissions(file));
    }
}
