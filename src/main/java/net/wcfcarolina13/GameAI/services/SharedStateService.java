package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner of the cross-skill shared state map.
 *
 * <p>This map used to be a static field on the legacy LLM function caller (retired in 1.1.251),
 * whose static initializer needed ollama4j; in a normal build every non-LLM caller (idle hobbies,
 * auto-hunt, come-recovery, {@code /bot} skill commands) hit a {@code NoClassDefFoundError} and
 * fell back to a throwaway empty map. The map lives here, free of any LLM dependency.
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
     * Kept for call-site compatibility. Formerly guarded a call into the legacy LLM function caller
     * that could fail on non-AI builds; the map is now owned here and nothing can fail.
     */
    public static Map<String, Object> safeSharedState(String callerTag) {
        return SHARED_STATE;
    }
}
