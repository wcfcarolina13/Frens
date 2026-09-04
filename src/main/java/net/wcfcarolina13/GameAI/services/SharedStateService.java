package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner of the cross-skill shared state map.
 *
 * <p>This map used to be a static field on {@code FunctionCallerV2}. That class's static
 * initializer constructs an {@code OllamaAPI}, and ollama4j is compile-only unless the mod is
 * built with {@code -PaiEnabled=true}, so in a normal build the first non-LLM caller (idle
 * hobbies, auto-hunt, come-recovery, {@code /bot} skill commands) hit a
 * {@code NoClassDefFoundError} and silently fell back to a throwaway empty map. The map now lives
 * here, free of any LLM dependency; {@code FunctionCallerV2} borrows it.
 */
public final class SharedStateService {

    private static final Map<String, Object> SHARED_STATE = new ConcurrentHashMap<>();

    private SharedStateService() {
    }

    /** The single shared skill-state map. */
    public static Map<String, Object> sharedState() {
        return SHARED_STATE;
    }

    /**
     * Kept for call-site compatibility. Formerly guarded a call into {@code FunctionCallerV2} that
     * could fail on non-AI builds; the map is now owned here and nothing can fail.
     */
    public static Map<String, Object> safeSharedState(String callerTag) {
        return SHARED_STATE;
    }
}
