package net.wcfcarolina13.Database;

import java.util.List;

/**
 * Pure vector helpers behind the memory database's stored embeddings and its Java
 * {@code cosine_distance} SQL function. Embeddings are stored as TEXT literals
 * ({@code "[v1,v2,...]"}), so no native SQLite vector extension is involved.
 */
public final class VectorMath {

    private VectorMath() {
    }

    /** Formats a vector as the {@code "[v1,v2,...]"} text literal stored in the {@code embedding} column. */
    public static String toLiteral(List<Double> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            sb.append(vec.get(i));
            if (i < vec.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /** Parses a {@code "[v1,v2,...]"} literal; null or blank yields an empty vector. */
    public static double[] parseLiteral(String literal) {
        if (literal == null) {
            return new double[0];
        }
        String cleaned = literal.replaceAll("[\\[\\]]", "");
        if (cleaned.isBlank()) {
            return new double[0];
        }
        String[] parts = cleaned.split(",");
        double[] vec = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vec[i] = Double.parseDouble(parts[i].trim());
        }
        return vec;
    }

    /**
     * Cosine distance {@code 1 - cos(a, b)}: 0 for identical directions, 1 for orthogonal.
     * A zero-length vector has similarity 0 (distance 1).
     *
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static double cosineDistance(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions do not match");
        }
        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            norm1 += a[i] * a[i];
            norm2 += b[i] * b[i];
        }
        double denom = Math.sqrt(norm1) * Math.sqrt(norm2);
        double sim = denom == 0.0 ? 0.0 : dot / denom;
        return 1.0 - sim;
    }
}
