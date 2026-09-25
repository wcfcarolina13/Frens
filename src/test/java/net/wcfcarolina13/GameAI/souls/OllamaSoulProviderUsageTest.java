package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Token-usage parsing and the near-context-limit check on the Ollama adapter. */
class OllamaSoulProviderUsageTest {

    private static final int NUM_CTX = 8192;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nearLimitBoundaryIsNinetyPercentOfTheWindow() {
        // 90% of 8192 = 7372.8, so 7372 tokens of prompt+output fit and 7373 do not.
        assertFalse(OllamaSoulProvider.nearContextLimit(7152, 220, NUM_CTX));
        assertTrue(OllamaSoulProvider.nearContextLimit(7153, 220, NUM_CTX));
        assertFalse(OllamaSoulProvider.nearContextLimit(5000, 220, NUM_CTX));
        assertTrue(OllamaSoulProvider.nearContextLimit(9000, 0, NUM_CTX));
    }

    @Test
    void unknownPromptCountOrWindowIsNeverNearTheLimit() {
        assertFalse(OllamaSoulProvider.nearContextLimit(-1, 220, NUM_CTX));
        assertFalse(OllamaSoulProvider.nearContextLimit(7500, 220, 0));
    }

    @Test
    void negativeOutputBudgetCountsAsZero() {
        assertFalse(OllamaSoulProvider.nearContextLimit(7372, -50, NUM_CTX));
        assertTrue(OllamaSoulProvider.nearContextLimit(7373, -50, NUM_CTX));
    }

    @Test
    void readUsageConvertsNanosecondDurationsToMillis() throws Exception {
        OllamaSoulProvider.Usage usage = OllamaSoulProvider.readUsage(mapper.readTree(
                "{\"prompt_eval_count\":4312,\"eval_count\":87,"
                        + "\"load_duration\":12500000,\"prompt_eval_duration\":1987000000}"));

        assertEquals(4312L, usage.promptTokens());
        assertEquals(87L, usage.evalTokens());
        assertEquals(12L, usage.loadMs());
        assertEquals(1987L, usage.promptEvalMs());
    }

    @Test
    void readUsageReportsMissingOrNonNumericFieldsAsMinusOne() throws Exception {
        OllamaSoulProvider.Usage usage = OllamaSoulProvider.readUsage(mapper.readTree(
                "{\"eval_count\":\"lots\",\"load_duration\":null}"));

        assertEquals(-1L, usage.promptTokens());
        assertEquals(-1L, usage.evalTokens());
        assertEquals(-1L, usage.loadMs());
        assertEquals(-1L, usage.promptEvalMs());
    }

    @Test
    void successfulResponseCarriesTokenCountsIntoTheResult() {
        SoulTypes.ProviderResult result = generateWith(
                "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"Evening.\"},"
                        + "\"done\":true,\"prompt_eval_count\":3021,\"eval_count\":14,"
                        + "\"load_duration\":1000000,\"prompt_eval_duration\":250000000}");

        assertTrue(result.success());
        assertEquals("Evening.", result.text());
        assertEquals(3021, result.inputTokens());
        assertEquals(14, result.outputTokens());
    }

    @Test
    void responseWithoutUsageFieldsStillSucceedsWithNullCounts() {
        SoulTypes.ProviderResult result = generateWith("{\"message\":{\"content\":\"Hm.\"}}");

        assertTrue(result.success());
        assertEquals("Hm.", result.text());
        assertNull(result.inputTokens());
        assertNull(result.outputTokens());
    }

    @SuppressWarnings("unchecked")
    private SoulTypes.ProviderResult generateWith(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        OllamaSoulProvider provider = new OllamaSoulProvider(
                URI.create("http://127.0.0.1:11434"), "test-model",
                request -> CompletableFuture.completedFuture(response), mapper);
        SoulTypes.ProviderRequest request = new SoulTypes.ProviderRequest(
                UUID.randomUUID(), "test-model",
                List.of(new SoulTypes.Message(SoulTypes.Role.USER, "hello")),
                Duration.ofSeconds(60), 220);
        try {
            return provider.generate(request).result().join();
        } finally {
            provider.close();
        }
    }
}
