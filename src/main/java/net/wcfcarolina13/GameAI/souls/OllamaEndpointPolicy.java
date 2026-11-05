package net.wcfcarolina13.GameAI.souls;

import java.net.URI;
import java.util.Locale;

/**
 * One place that turns the configured Ollama base URL into an API endpoint. Before this policy
 * the soul provider resolved {@code /api/chat} against the base URI (which drops any path prefix,
 * so a reverse proxy at {@code /ollama} lost it) while the model installer concatenated
 * {@code base + "/api/..."} (so a trailing slash gave {@code //api/pull}). Both now go through
 * {@link #endpoint(String, String)}: trailing slashes are stripped, a path prefix is kept, and a
 * scheme other than HTTP or HTTPS is rejected up front instead of surfacing later as a
 * {@code ClassCastException} from {@code openConnection()}.
 *
 * <p>Pure: strings in, strings out, no network, no config access.
 */
public final class OllamaEndpointPolicy {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    /** User-showable reason for a rejected scheme (also the SoulSettings validation message). */
    public static final String SCHEME_ERROR = "The Ollama URL must use HTTP or HTTPS.";

    private OllamaEndpointPolicy() {
    }

    /** True for {@code http} and {@code https} in any case, false for anything else or null. */
    public static boolean isSupportedScheme(String scheme) {
        if (scheme == null) {
            return false;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        return s.equals("http") || s.equals("https");
    }

    /**
     * True when {@code baseUrl} parses as a URI whose scheme is HTTP or HTTPS. A blank value is
     * accepted because {@link #endpoint(String, String)} substitutes the default local daemon.
     */
    public static boolean isHttpUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            return true;
        }
        try {
            return isSupportedScheme(URI.create(base).getScheme());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Joins the base URL and an API path. Blank base means the default local daemon. Any number
     * of trailing slashes and a trailing {@code /api} on the base are dropped, a missing leading slash
     * on the path is added,
     * and a path prefix on the base ({@code http://host/ollama}) is kept in front of the API path.
     *
     * @throws IllegalArgumentException when the base is not an HTTP or HTTPS URL
     */
    public static String endpoint(String baseUrl, String apiPath) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        if (!isHttpUrl(base)) {
            throw new IllegalArgumentException(SCHEME_ERROR);
        }
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        base = base.substring(0, end);
        // A pasted ".../api" base (the old resolve() call quietly fixed it) would give /api/api/chat.
        if (base.endsWith("/api")) {
            base = base.substring(0, base.length() - "/api".length());
            int trim = base.length();
            while (trim > 0 && base.charAt(trim - 1) == '/') {
                trim--;
            }
            base = base.substring(0, trim);
        }
        String path = apiPath == null ? "" : apiPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    /** {@link #endpoint(String, String)} for callers that hold a parsed base URI. */
    public static URI endpoint(URI baseUri, String apiPath) {
        return URI.create(endpoint(baseUri == null ? "" : baseUri.toString(), apiPath));
    }
}
