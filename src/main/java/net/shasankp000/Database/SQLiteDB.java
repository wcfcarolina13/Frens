package net.shasankp000.Database;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SQLiteDB {

    private static final Logger logger = LoggerFactory.getLogger("ai-player");

    private static final String DB_URL = "jdbc:sqlite:" +
            FabricLoader.getInstance().getGameDir().resolve("sqlite_databases/memory_agent.db").toAbsolutePath();

    // --- simple OS helpers
    private static boolean isMac() {
        return System.getProperty("os.name","").toLowerCase(Locale.ENGLISH).contains("mac");
    }
    private static boolean isWindows() {
        return System.getProperty("os.name","").toLowerCase(Locale.ENGLISH).contains("win");
    }
    private static boolean isLinux() {
        String os = System.getProperty("os.name","").toLowerCase(Locale.ENGLISH);
        return os.contains("nux") || os.contains("nix");
    }

    public static void createDB() {
        File dbDir = FabricLoader.getInstance().getGameDir().resolve("sqlite_databases").toFile();
        if (!dbDir.exists() && dbDir.mkdirs()) {
            logger.info("✅ Database directory created: {}", dbDir);
        }

        // Configure SQLite to allow extensions
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties())) {
            // Always load sqlite-vec
            Path vecPath = VectorExtensionHelper.ensureSqliteVecPresent();
            VectorExtensionHelper.loadSqliteVecExtension(conn, vecPath);

            if (isMac()) {
                // macOS: skip vss to avoid libomp clash with DJL/PyTorch
                logger.warn("⚠️ macOS detected — skipping sqlite-vss (avoids OpenMP/libomp conflicts). Using fallback cosine_distance UDF.");
                registerCosineDistanceUdf(conn);
            } else if (isWindows()) {
                logger.info("✅ Windows detected — using fallback cosine_distance UDF.");
                registerCosineDistanceUdf(conn); // works with our TEXT '[...]' literals
            } else if (isLinux()) {
                logger.info("✅ Linux detected — loading sqlite-vss native extension.");
                Path vssPath = VectorExtensionHelper.ensureSqliteVssPresent();
                VectorExtensionHelper.loadSqliteVssExtension(conn, vssPath);
            } else {
                // unknown OS: play safe
                logger.warn("⚠️ Unknown OS — not loading sqlite-vss. Using fallback cosine_distance UDF.");
                registerCosineDistanceUdf(conn);
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                stmt.execute("PRAGMA journal_mode = WAL;");

                String createTable = """
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        timestamp TEXT DEFAULT CURRENT_TIMESTAMP,
                        prompt TEXT,
                        response TEXT,
                        embedding VECTOR
                    );
                """;
                stmt.executeUpdate(createTable);
                logger.info("✅ Memory table created.");
            }
        } catch (SQLException e) {
            logger.error("❌ DB creation failed: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error("❌ Extension setup failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static void storeMemory(String type, String prompt, String response, List<Double> embedding) {
        String sql = """
            INSERT INTO memories (type, prompt, response, embedding)
            VALUES (?, ?, ?, ?);
        """;

        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties());
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // We store the embedding as a TEXT vector literal: "[v1,v2,...]"
            pstmt.setString(1, type);
            pstmt.setString(2, prompt);
            pstmt.setString(3, response);
            pstmt.setString(4, vectorToLiteral(embedding));

            pstmt.executeUpdate();
            logger.info("📝 Memory stored with vector embedding.");
        } catch (SQLException e) {
            logger.error("❌ Failed to store memory: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    public static List<Memory> findRelevantMemories(List<Double> queryEmbedding, String typeFilter, int topK) {
        logger.info("Query embedding size: {}", queryEmbedding.size());

        List<Memory> results = new ArrayList<>();
        String sql = """
            SELECT id, type, timestamp, prompt, response,
                   1 - cosine_distance(embedding, ?) AS similarity
            FROM memories
            WHERE type = ?
            ORDER BY similarity DESC
            LIMIT ?;
        """;

        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);

        try (Connection conn = DriverManager.getConnection(DB_URL, config.toProperties())) {

            // Ensure the distance function exists on THIS connection.
            // - On macOS/Windows we register our fallback UDF.
            // - On Linux we try to rely on sqlite-vss (loaded during createDB),
            //   but if it isn't available for any reason, we still register the UDF.
            boolean needUdf = isMac() || isWindows();
            if (!needUdf) {
                // Linux: you may still end up without vss on a new connection; register UDF as a safety net.
                needUdf = true;
            }
            if (needUdf) {
                registerCosineDistanceUdf(conn);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, vectorToLiteral(queryEmbedding));
                pstmt.setString(2, typeFilter);
                pstmt.setInt(3, topK);

                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    results.add(new Memory(
                            rs.getInt("id"),
                            rs.getString("type"),
                            rs.getString("timestamp"),
                            rs.getString("prompt"),
                            rs.getString("response"),
                            rs.getDouble("similarity")
                    ));
                }
            }

        } catch (SQLException e) {
            logger.error("❌ Vector search failed: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage());
        }

        return results;
    }

    public static List<SQLiteDB.Memory> fetchInitialResponse() {
        List<SQLiteDB.Memory> results = new ArrayList<>();
        String sql = """
            SELECT id, type, timestamp, prompt, response, 0.0 AS similarity
            FROM memories
            WHERE type = 'conversation'
            ORDER BY id ASC
            LIMIT 1;
        """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                results.add(new SQLiteDB.Memory(
                        rs.getInt("id"),
                        rs.getString("type"),
                        rs.getString("timestamp"),
                        rs.getString("prompt"),
                        rs.getString("response"),
                        0.0
                ));
            }
        } catch (SQLException e) {
            logger.error("Caught exception while fetching initial response: {}", e.getMessage());
            throw new RuntimeException(e);
        }

        return results;
    }

    private static String vectorToLiteral(List<Double> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            sb.append(vec.get(i));
            if (i < vec.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Registers a simple TEXT-based cosine_distance(vector_text, vector_text) on the given connection.
     * Expects both arguments to be "[v1,v2,...]" text literals. Works fine with our storage approach.
     */
    private static void registerCosineDistanceUdf(Connection conn) {
        try {
            Function.create(conn, "cosine_distance", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    if (args() != 2) {
                        throw new SQLException("cosine_distance() requires exactly 2 arguments");
                    }
                    String v1Str = value_text(0);
                    String v2Str = value_text(1);

                    double[] v1 = parseVectorLiteral(v1Str);
                    double[] v2 = parseVectorLiteral(v2Str);

                    if (v1.length != v2.length) {
                        throw new SQLException("Vector dimensions do not match");
                    }

                    double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
                    for (int i = 0; i < v1.length; i++) {
                        dot += v1[i] * v2[i];
                        norm1 += v1[i] * v1[i];
                        norm2 += v2[i] * v2[i];
                    }

                    double denom = Math.sqrt(norm1) * Math.sqrt(norm2);
                    double sim = denom == 0.0 ? 0.0 : dot / denom;
                    result(1.0 - sim);
                }

                private double[] parseVectorLiteral(String literal) {
                    if (literal == null) return new double[0];
                    String cleaned = literal.replaceAll("[\\[\\]]", "");
                    if (cleaned.isBlank()) return new double[0];
                    String[] parts = cleaned.split(",");
                    double[] vec = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        vec[i] = Double.parseDouble(parts[i].trim());
                    }
                    return vec;
                }
            });
            logger.info("✅ Registered fallback cosine_distance UDF on current connection.");
        } catch (SQLException e) {
            logger.warn("ℹ️ Could not register cosine_distance UDF (may already exist): {}", e.getMessage());
        }
    }

    public record Memory(
            int id,
            String type,
            String timestamp,
            String prompt,
            String response,
            double similarity
    ) {}

}