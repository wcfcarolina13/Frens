package net.shasankp000.Database;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.sql.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public class VectorExtensionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorExtensionHelper.class);

    // ---- platform helpers
    private static boolean isWindows() {
        return System.getProperty("os.name","").toLowerCase(Locale.ENGLISH).contains("win");
    }
    private static boolean isMac() {
        return System.getProperty("os.name","").toLowerCase(Locale.ENGLISH).contains("mac");
    }
    private static boolean isLinux() {
        String os = System.getProperty("os.name","").toLowerCase(Locale.ENGLISH);
        return os.contains("nux") || os.contains("nix");
    }
    private static boolean isArm64() {
        String arch = System.getProperty("os.arch","");
        return "aarch64".equalsIgnoreCase(arch) || "arm64".equalsIgnoreCase(arch);
    }
    private static String libSuffix() {
        if (isWindows()) return ".dll";
        return isMac() ? ".dylib" : ".so";
    }

    // === SQLITE-VEC ===
    private static String vecUrl() {
        if (isWindows()) return "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-windows-x86_64.tar.gz";
        if (isLinux())   return "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-linux-x86_64.tar.gz";
        if (isMac())     return isArm64()
                ? "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-macos-aarch64.tar.gz"
                : "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-0.1.6-loadable-macos-x86_64.tar.gz";
        throw new UnsupportedOperationException("Unsupported OS for SQLite-Vec");
    }
    private static String vecTargetFileName() {
        if (isWindows()) return "vector0.dll";
        if (isMac())     return "vector0.dylib";
        return "vector0.so";
    }
    private static String normalizeLibraryName(String name) {
        String suffix = libSuffix();
        String lower = name.toLowerCase(Locale.ENGLISH);
        String lowerSuffix = suffix.toLowerCase(Locale.ENGLISH);
        while (lower.endsWith(lowerSuffix + lowerSuffix)) {
            name = name.substring(0, name.length() - suffix.length());
            lower = name.toLowerCase(Locale.ENGLISH);
        }
        return name;
    }

    private static Path stripLibraryExtension(Path lib) {
        String fileName = lib.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            String withoutExt = fileName.substring(0, dot);
            return lib.getParent() != null ? lib.getParent().resolve(withoutExt) : Path.of(withoutExt);
        }
        return lib;
    }

    public static Path ensureSqliteVecPresent() throws IOException {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path vecDir = configDir.resolve("sqlite_vector/sqlite-vec");
        Files.createDirectories(vecDir);

        String targetFileName = normalizeLibraryName(vecTargetFileName());
        Path outputPath = vecDir.resolve(targetFileName);
        if (Files.exists(outputPath)) {
            LOGGER.info("✅ sqlite-vec already present at: {}", outputPath);
            return outputPath;
        }

        String downloadUrl = vecUrl();
        LOGGER.info("⬇️ Downloading sqlite-vec from {}", downloadUrl);
        Path gzPath = vecDir.resolve("sqlite-vec.tar.gz");
        Path tarPath = vecDir.resolve("sqlite-vec.tar");

        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, gzPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try (GZIPInputStream gzipIn = new GZIPInputStream(Files.newInputStream(gzPath));
             OutputStream out = Files.newOutputStream(tarPath)) {
            gzipIn.transferTo(out);
        }

        try (InputStream tarIn = Files.newInputStream(tarPath)) {
            boolean found = safeExtractTar(tarIn, targetFileName, outputPath);
            if (!found) throw new IOException("❌ sqlite-vec extraction failed!");
        }

        clearQuarantineIfMac(outputPath);
        LOGGER.info("✅ sqlite-vec ready at: {}", outputPath);
        return outputPath;
    }

    // === SQLITE-VSS ===
    private static String vssUrl() {
        if (isLinux())   return "https://github.com/asg017/sqlite-vss/releases/download/v0.1.2/sqlite-vss-v0.1.2-loadable-linux-x86_64.tar.gz";
        if (isMac())     return isArm64()
                ? "https://github.com/asg017/sqlite-vss/releases/download/v0.1.2/sqlite-vss-v0.1.2-loadable-macos-aarch64.tar.gz"
                : "https://github.com/asg017/sqlite-vss/releases/download/v0.1.2/sqlite-vss-v0.1.2-loadable-macos-x86_64.tar.gz";
        throw new UnsupportedOperationException("sqlite-vss is not supported on this OS");
    }
    private static String vssTargetFileName() {
        return isMac() ? "vss0.dylib" : "vss0.so";
    }
    private static String vssBundledVecName() {
        return isMac() ? "vector0.dylib" : "vector0.so";
    }

    public static Path ensureSqliteVssPresent() throws IOException {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path vssDir = configDir.resolve("sqlite_vector/sqlite-vss");
        Files.createDirectories(vssDir);

        Path vssOut = vssDir.resolve(normalizeLibraryName(vssTargetFileName()));
        if (Files.exists(vssOut)) {
            LOGGER.info("✅ sqlite-vss already present at: {}", vssOut);
            return vssOut;
        }

        String downloadUrl = vssUrl();
        LOGGER.info("⬇️ Downloading sqlite-vss from {}", downloadUrl);
        Path gzPath = vssDir.resolve("sqlite-vss.tar.gz");
        Path tarPath = vssDir.resolve("sqlite-vss.tar");

        try (InputStream in = new URL(downloadUrl).openStream()) {
            Files.copy(in, gzPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try (GZIPInputStream gzipIn = new GZIPInputStream(Files.newInputStream(gzPath));
             OutputStream out = Files.newOutputStream(tarPath)) {
            gzipIn.transferTo(out);
        }

        // extract vss
        try (InputStream tarIn = Files.newInputStream(tarPath)) {
            boolean found = safeExtractTar(tarIn, vssTargetFileName(), vssOut);
            if (!found) throw new IOException("❌ sqlite-vss extraction failed!");
        }

        // extract bundled vector0 (best ABI match)
        Path bundledVecOut = vssDir.resolve(normalizeLibraryName(vssBundledVecName()));
        try (InputStream tarIn2 = Files.newInputStream(tarPath)) {
            boolean vecFound = safeExtractTar(tarIn2, vssBundledVecName(), bundledVecOut);
            if (vecFound) {
                clearQuarantineIfMac(bundledVecOut);
                LOGGER.info("✅ Extracted bundled sqlite-vec for vss: {}", bundledVecOut);
            } else {
                LOGGER.info("ℹ️ No bundled vector0 found in sqlite-vss tar (ok).");
            }
        }

        clearQuarantineIfMac(vssOut);
        LOGGER.info("✅ sqlite-vss ready at: {}", vssOut);
        return vssOut;
    }

    private static void clearQuarantineIfMac(Path lib) {
        if (!isMac()) return;
        try {
            new ProcessBuilder("xattr", "-dr", "com.apple.quarantine", lib.toString())
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
    }

    /**
     * Shared TAR extractor. Accepts either exact target name or its vec0.* alias.
     */
    private static boolean safeExtractTar(InputStream tarInputStream, String targetFileName, Path outputPath) throws IOException {
        byte[] header = new byte[512];
        String altName = targetFileName.replace("vector0", "vec0");

        while (true) {
            int read = tarInputStream.read(header);
            if (read < 512) break;

            String name = new String(header, 0, 100).trim();
            if (name.isEmpty()) break;

            long size = Long.parseLong(new String(header, 124, 12).trim(), 8);
            LOGGER.info("🔍 TAR entry: {} ({} bytes)", name, size);

            boolean matches =
                    name.equals(targetFileName) || name.endsWith("/" + targetFileName) ||
                    name.equals(altName)        || name.endsWith("/" + altName);

            if (matches) {
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    byte[] buffer = new byte[4096];
                    long remaining = size;
                    while (remaining > 0) {
                        int len = tarInputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (len == -1) break;
                        out.write(buffer, 0, len);
                        remaining -= len;
                    }
                }
                LOGGER.info("✅ Extracted: {}", outputPath);
                return true;
            } else {
                long skip = size + (512 - (size % 512)) % 512;
                while (skip > 0) {
                    long skipped = tarInputStream.skip(skip);
                    if (skipped <= 0) break;
                    skip -= skipped;
                }
            }
        }
        return false;
    }

    // ---- robust extension loader (tries explicit entrypoint, then default)
    private static void enableExtensionLoading(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA load_extension = 1");
        }
    }
    private static void tryLoadExtension(Connection conn, Path lib, String... entrypoints) throws SQLException {
        enableExtensionLoading(conn);
        Path loadPath = stripLibraryExtension(lib);
        final String libPath = loadPath.toAbsolutePath().toString().replace("\\", "\\\\");

        SQLException last = null;
        for (String ep : entrypoints) {
            try {
                if (ep == null || ep.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT load_extension(?)")) {
                        LOGGER.info("🔗 Loading {} with default entrypoint", libPath);
                        ps.setString(1, libPath);
                        ps.execute();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT load_extension(?, ?)")) {
                        LOGGER.info("🔗 Loading {} with entrypoint {}", libPath, ep);
                        ps.setString(1, libPath);
                        ps.setString(2, ep);
                        ps.execute();
                    }
                }
                return; // success
            } catch (SQLException e) {
                last = e;
                LOGGER.info("ℹ️ load_extension attempt failed ({}): {}", (ep == null ? "default" : ep), e.getMessage());
            }
        }
        throw last != null ? last : new SQLException("load_extension failed for " + libPath);
    }

    // ---- Public loaders
    public static void loadSqliteVecExtension(Connection conn, Path vecPath) throws SQLException {
        LOGGER.info("Attempting to load sqlite-vec from {}", vecPath.toAbsolutePath());
        // try explicit then default
        try {
            tryLoadExtension(conn, vecPath, "sqlite3_vec_init", null);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT vec_version();")) {
                if (rs.next()) LOGGER.info("✅ sqlite-vec version: {}", rs.getString(1));
            }
        } catch (SQLException e) {
            LOGGER.error("❌ Failed to load sqlite-vec extension: {}", e.getMessage());
            // Re-throw so the caller (SQLiteDB.createDB) can catch it and disable DB features.
            throw e;
        }
    }

    public static void loadSqliteVssExtension(Connection conn, Path vssPath) throws SQLException, IOException {
        // Ensure the vss-bundled vector0 is loaded on THIS SAME CONNECTION (best ABI match).
        Path localVec = vssPath.getParent().resolve(vssBundledVecName());
        if (Files.exists(localVec)) {
            try {
                loadSqliteVecExtension(conn, localVec);
            } catch (SQLException e) {
                LOGGER.info("ℹ️ Bundled vector0 load attempt returned: {}", e.getMessage());
            }
        }

        // Load vss (try explicit then default)
        tryLoadExtension(conn, vssPath, "sqlite3_vss_init", null);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT vss_version();")) {
            if (rs.next()) LOGGER.info("✅ sqlite-vss version: {}", rs.getString(1));
        }
    }

    // ---- Windows fallback UDF (unchanged)
    public static void registerCosineDistanceIfNeeded(Connection conn) throws SQLException {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        if (!osName.contains("win")) return;

        try {
            Function.create(conn, "cosine_distance", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    if (args() != 2) throw new SQLException("cosine_distance() requires exactly 2 arguments");
                    String v1Str = value_text(0);
                    String v2Str = value_text(1);
                    double[] v1 = parseVectorLiteral(v1Str);
                    double[] v2 = parseVectorLiteral(v2Str);
                    if (v1.length != v2.length) throw new SQLException("Vector dimensions do not match");
                    double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
                    for (int i = 0; i < v1.length; i++) {
                        dot += v1[i] * v2[i];
                        norm1 += v1[i] * v1[i];
                        norm2 += v2[i] * v2[i];
                    }
                    double sim = dot / (Math.sqrt(norm1) * Math.sqrt(norm2) + 1e-10);
                    result(1.0 - sim);
                }
                private double[] parseVectorLiteral(String literal) {
                    String cleaned = literal.replaceAll("[\\[\\]]", "");
                    String[] parts = cleaned.split(",");
                    double[] vec = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) vec[i] = Double.parseDouble(parts[i].trim());
                    return vec;
                }
            });
            LOGGER.info("✅ Registered fallback cosine_distance for Windows (TEXT VECTOR)");
        } catch (SQLException e) {
            LOGGER.error("❌ Failed to register cosine_distance UDF: {}", e.getMessage(), e);
            throw e;
        }
    }

    private static double[] deserializeVector(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int len = bytes.length / Double.BYTES;
        double[] vec = new double[len];
        for (int i = 0; i < len; i++) vec[i] = buffer.getDouble();
        return vec;
    }
}
