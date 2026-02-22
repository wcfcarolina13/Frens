package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.FunctionCaller.FunctionCallerV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-platform safe accessor for shared skill state.
 */
public final class SharedStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger("shared-state");
    private static final Set<String> WARNED_CALLERS = ConcurrentHashMap.newKeySet();

    private SharedStateService() {
    }

    public static Map<String, Object> safeSharedState(String callerTag) {
        try {
            return FunctionCallerV2.getSharedState();
        } catch (Throwable t) {
            String tag = callerTag == null || callerTag.isBlank() ? "unknown-caller" : callerTag;
            if (WARNED_CALLERS.add(tag)) {
                LOGGER.warn("Shared state unavailable for {}: {}",
                        tag,
                        t.getClass().getSimpleName(),
                        t);
            } else {
                LOGGER.debug("Shared state still unavailable for {}: {}", tag, t.getClass().getSimpleName());
            }
            return new HashMap<>();
        }
    }
}
