package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared skill-state map must be reachable without loading the LLM stack.
 * Regression guard for the 1.1.199 field log:
 * {@code Shared state unavailable for idle-hobbies: NoClassDefFoundError
 * io/github/amithkoujalgi/ollama4j/core/exceptions/OllamaBaseException} — the map used to live
 * on the legacy LLM function caller (deleted in 1.1.251), whose static initializer needed
 * ollama4j; non-AI builds excluded it from the JAR, so every non-LLM caller got a throwaway map.
 */
class SharedStateServiceTest {

    @Test
    void sharedStateIsOneStableMap() {
        Map<String, Object> a = SharedStateService.safeSharedState("test-a");
        Map<String, Object> b = SharedStateService.safeSharedState("test-b");
        assertSame(a, b, "every caller must see the same map");
        assertSame(a, SharedStateService.sharedState());
        a.put("sharedStateServiceTest.probe", 42);
        assertEquals(42, SharedStateService.sharedState().get("sharedStateServiceTest.probe"));
        a.remove("sharedStateServiceTest.probe");
    }

    @Test
    void sharedStateServiceDoesNotDependOnTheLlmStack() throws Exception {
        Path src = Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SharedStateService.java");
        String source = Files.readString(src);
        assertFalse(source.contains("net.wcfcarolina13.FunctionCaller") || source.contains("FunctionCallerV2."),
                "SharedStateService must not reference FunctionCallerV2 — its static init needs ollama4j, "
                        + "which is compile-only unless -PaiEnabled=true");
    }
}
