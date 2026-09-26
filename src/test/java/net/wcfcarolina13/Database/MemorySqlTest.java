package net.wcfcarolina13.Database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Memory storage + ranking on plain SQLite with the Java cosine_distance function (no native extension). */
class MemorySqlTest {

    @Test
    void literalRoundTrips() {
        String literal = VectorMath.toLiteral(List.of(1.0, -2.5, 0.25));
        assertEquals("[1.0,-2.5,0.25]", literal);
        assertArrayEquals(new double[]{1.0, -2.5, 0.25}, VectorMath.parseLiteral(literal));
    }

    @Test
    void nullOrBlankLiteralIsEmpty() {
        assertEquals(0, VectorMath.parseLiteral(null).length);
        assertEquals(0, VectorMath.parseLiteral("[]").length);
        assertEquals(0, VectorMath.parseLiteral("  ").length);
    }

    @Test
    void cosineDistanceBasics() {
        assertEquals(0.0, VectorMath.cosineDistance(new double[]{1, 2}, new double[]{2, 4}), 1e-9);
        assertEquals(1.0, VectorMath.cosineDistance(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
        assertEquals(2.0, VectorMath.cosineDistance(new double[]{1, 0}, new double[]{-1, 0}), 1e-9);
        // zero vector: similarity 0, no division by zero
        assertEquals(1.0, VectorMath.cosineDistance(new double[]{0, 0}, new double[]{1, 1}), 1e-9);
    }

    @Test
    void dimensionMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorMath.cosineDistance(new double[]{1}, new double[]{1, 2}));
    }

    @Test
    void storesAndRanksOnPlainSqlite() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MemorySql.registerCosineDistance(conn);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(MemorySql.CREATE_MEMORIES_TABLE);
            }
            insert(conn, "conversation", "far", List.of(0.0, 1.0));
            insert(conn, "conversation", "near", List.of(1.0, 0.1));
            insert(conn, "conversation", "exact", List.of(2.0, 0.0));
            insert(conn, "event", "other-type", List.of(1.0, 0.0));

            List<String> ranked = new ArrayList<>();
            List<Double> sims = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(MemorySql.FIND_RELEVANT)) {
                ps.setString(1, VectorMath.toLiteral(List.of(1.0, 0.0)));
                ps.setString(2, "conversation");
                ps.setInt(3, 2);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ranked.add(rs.getString("prompt"));
                        sims.add(rs.getDouble("similarity"));
                    }
                }
            }
            assertEquals(List.of("exact", "near"), ranked);
            assertEquals(1.0, sims.get(0), 1e-9);
        }
    }

    @Test
    void storedEmbeddingReadsBackAsTheSameLiteral() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(MemorySql.CREATE_MEMORIES_TABLE);
            }
            insert(conn, "conversation", "p", List.of(0.5, -1.0, 3.0));
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT embedding, typeof(embedding) AS t FROM memories")) {
                rs.next();
                assertEquals("[0.5,-1.0,3.0]", rs.getString("embedding"));
                assertEquals("text", rs.getString("t"));
            }
        }
    }

    private static void insert(Connection conn, String type, String prompt, List<Double> embedding) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(MemorySql.INSERT_MEMORY)) {
            ps.setString(1, type);
            ps.setString(2, prompt);
            ps.setString(3, "response");
            ps.setString(4, VectorMath.toLiteral(embedding));
            ps.executeUpdate();
        }
    }
}
