package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage for the one place that joins the Ollama base URL and an API path. */
class OllamaEndpointPolicyTest {

    @Test
    void plainBaseGetsThePathAppended() {
        assertEquals("http://127.0.0.1:11434/api/chat",
                OllamaEndpointPolicy.endpoint("http://127.0.0.1:11434", "/api/chat"));
    }

    @Test
    void trailingSlashesAreStrippedSoThereIsNoDoubleSlash() {
        assertEquals("http://127.0.0.1:11434/api/pull",
                OllamaEndpointPolicy.endpoint("http://127.0.0.1:11434/", "/api/pull"));
        assertEquals("http://127.0.0.1:11434/api/pull",
                OllamaEndpointPolicy.endpoint("http://127.0.0.1:11434///", "api/pull"));
    }

    @Test
    void reverseProxyPathPrefixIsKept() {
        // URI.resolve("/api/chat") would have dropped /ollama; the policy keeps it.
        assertEquals("https://box.local/ollama/api/chat",
                OllamaEndpointPolicy.endpoint("https://box.local/ollama/", "/api/chat"));
        assertEquals(URI.create("https://box.local/ollama/api/tags"),
                OllamaEndpointPolicy.endpoint(URI.create("https://box.local/ollama"), "/api/tags"));
    }

    @Test
    void httpAndHttpsInAnyCaseAreAccepted() {
        assertTrue(OllamaEndpointPolicy.isHttpUrl("http://host:11434"));
        assertTrue(OllamaEndpointPolicy.isHttpUrl("HTTPS://host"));
        assertTrue(OllamaEndpointPolicy.isSupportedScheme("Http"));
        assertEquals("HTTPS://host/api/version", OllamaEndpointPolicy.endpoint("HTTPS://host", "/api/version"));
    }

    @Test
    void fileAndFtpAndSchemelessAreRejectedWithTheUserShowableMessage() {
        for (String bad : new String[] {"file:///tmp/ollama", "ftp://host/", "127.0.0.1:11434", "not a url"}) {
            assertFalse(OllamaEndpointPolicy.isHttpUrl(bad), bad);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OllamaEndpointPolicy.endpoint(bad, "/api/tags"), bad);
            assertEquals(OllamaEndpointPolicy.SCHEME_ERROR, ex.getMessage());
        }
        assertFalse(OllamaEndpointPolicy.isSupportedScheme(null));
    }

    @Test
    void blankBaseMeansTheLocalDaemon() {
        assertTrue(OllamaEndpointPolicy.isHttpUrl(""));
        assertTrue(OllamaEndpointPolicy.isHttpUrl(null));
        assertEquals(OllamaEndpointPolicy.DEFAULT_BASE_URL + "/api/version",
                OllamaEndpointPolicy.endpoint("  ", "/api/version"));
        assertEquals(OllamaEndpointPolicy.DEFAULT_BASE_URL + "/api/version",
                OllamaEndpointPolicy.endpoint((URI) null, "api/version").toString());
    }

    @Test
    void theSchemeMessageIsPlayerTextSafe() {
        assertFalse(OllamaEndpointPolicy.SCHEME_ERROR.contains("—"));
        assertFalse(OllamaEndpointPolicy.SCHEME_ERROR.contains(";"));
    }

    @org.junit.jupiter.api.Test
    void aPastedApiSuffixIsDropped() {
        org.junit.jupiter.api.Assertions.assertEquals("http://host:11434/api/chat",
                OllamaEndpointPolicy.endpoint("http://host:11434/api", "/api/chat"));
        org.junit.jupiter.api.Assertions.assertEquals("http://host:11434/api/chat",
                OllamaEndpointPolicy.endpoint("http://host:11434/api/", "/api/chat"));
        org.junit.jupiter.api.Assertions.assertEquals("https://h/ollama/api/pull",
                OllamaEndpointPolicy.endpoint("https://h/ollama/api", "api/pull"));
    }
}
