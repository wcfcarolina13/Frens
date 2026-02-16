package net.shasankp000.GameAI.services;

import net.shasankp000.FunctionCaller.FunctionCallerV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Cross-platform safe accessor for shared skill state.
 */
public final class SharedStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger("shared-state");

    private SharedStateService() {
    }

    public static Map<String, Object> safeSharedState(String callerTag) {
        try {
            return FunctionCallerV2.getSharedState();
        } catch (Throwable t) {
            String tag = callerTag == null || callerTag.isBlank() ? "unknown-caller" : callerTag;
            LOGGER.warn("Shared state unavailable for {}: {}",
                    tag,
                    t.getClass().getSimpleName(),
                    t);
            return new HashMap<>();
        }
    }
}
