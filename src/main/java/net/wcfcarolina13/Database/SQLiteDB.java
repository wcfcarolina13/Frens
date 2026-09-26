package net.wcfcarolina13.Database;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SQLiteDB {

    private static final Logger logger = LoggerFactory.getLogger("frens");
    public static volatile boolean MEMORY_AVAILABLE = false;

    private static final String DB_URL = "jdbc:sqlite:" +
            FabricLoader.getInstance().getGameDir().resolve("sqlite_databases/memory_agent.db").toAbsolutePath();

    /**
     * Creates the memory database and table. Plain SQLite only: embeddings are stored as TEXT
     * literals and ranked with the Java {@code cosine_distance} function registered on each
     * connection, so no native extension is downloaded, extracted or loaded. The
     * {@code embedding VECTOR} column is an ordinary declared type, so databases created by
     * earlier builds stay readable unchanged.
     */
    public static void createDB() {
        File dbDir = FabricLoader.getInstance().getGameDir().resolve("sqlite_databases").toFile();
        if (!dbDir.exists() && dbDir.mkdirs()) {
            logger.info("✅ Database directory created: {}", dbDir);
        }

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            registerCosineDistanceUdf(conn);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                stmt.execute("PRAGMA journal_mode = WAL;");

                stmt.executeUpdate(MemorySql.CREATE_MEMORIES_TABLE);
                logger.info("✅ Memory table created.");
                MEMORY_AVAILABLE = true; // Set flag on full success
            }
        } catch (SQLException e) {
            logger.error("❌ DB creation failed: SQLState={}, ErrorCode={}, Message={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
            // Do not re-throw; allows game to run without DB features.
        }
    }

    public static void storeMemory(String type, String prompt, String response, List<Double> embedding) {
        if (!MEMORY_AVAILABLE) {
            logger.warn("DB not available, skipping memory storage.");
            return;
        }
        String sql = MemorySql.INSERT_MEMORY;

        try (Connection conn = DriverManager.getConnection(DB_URL);
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
        if (!MEMORY_AVAILABLE) {
            logger.warn("DB not available, skipping memory search.");
            return new ArrayList<>();
        }
        logger.info("Query embedding size: {}", queryEmbedding.size());

        List<Memory> results = new ArrayList<>();
        String sql = MemorySql.FIND_RELEVANT;

        try (Connection conn = DriverManager.getConnection(DB_URL)) {

            // SQL functions are per-connection: register the distance function on THIS one.
            registerCosineDistanceUdf(conn);

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
        if (!MEMORY_AVAILABLE) {
            logger.warn("DB not available, skipping initial response fetch.");
            return new ArrayList<>();
        }
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
            // Do not rethrow, just return empty list.
        }

        return results;
    }

    private static String vectorToLiteral(List<Double> vec) {
        return VectorMath.toLiteral(vec);
    }

    /** Registers the Java {@code cosine_distance} function on {@code conn} (see {@link MemorySql}). */
    private static void registerCosineDistanceUdf(Connection conn) {
        try {
            MemorySql.registerCosineDistance(conn);
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