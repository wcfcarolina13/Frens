package net.wcfcarolina13.Database;

import org.sqlite.Function;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The memory database's SQL and its Java {@code cosine_distance} function, kept free of
 * Minecraft/Fabric types so the storage and ranking path can be exercised against an in-memory
 * SQLite database in unit tests. {@link SQLiteDB} owns the file location and connections.
 */
public final class MemorySql {

    private MemorySql() {
    }

    /** {@code embedding VECTOR} is an ordinary declared column type (NUMERIC affinity); values are TEXT literals. */
    public static final String CREATE_MEMORIES_TABLE = """
            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                timestamp TEXT DEFAULT CURRENT_TIMESTAMP,
                prompt TEXT,
                response TEXT,
                embedding VECTOR
            );
            """;

    public static final String INSERT_MEMORY = """
            INSERT INTO memories (type, prompt, response, embedding)
            VALUES (?, ?, ?, ?);
            """;

    /** Parameters: query embedding literal, type filter, limit. */
    public static final String FIND_RELEVANT = """
            SELECT id, type, timestamp, prompt, response,
                   1 - cosine_distance(embedding, ?) AS similarity
            FROM memories
            WHERE type = ?
            ORDER BY similarity DESC
            LIMIT ?;
            """;

    /**
     * Registers {@code cosine_distance(text, text)} on {@code conn}. SQL functions are
     * per-connection, so every connection that runs {@link #FIND_RELEVANT} needs this.
     */
    public static void registerCosineDistance(Connection conn) throws SQLException {
        Function.create(conn, "cosine_distance", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                if (args() != 2) {
                    throw new SQLException("cosine_distance() requires exactly 2 arguments");
                }
                double[] v1 = VectorMath.parseLiteral(value_text(0));
                double[] v2 = VectorMath.parseLiteral(value_text(1));
                if (v1.length != v2.length) {
                    throw new SQLException("Vector dimensions do not match");
                }
                result(VectorMath.cosineDistance(v1, v2));
            }
        });
    }
}
