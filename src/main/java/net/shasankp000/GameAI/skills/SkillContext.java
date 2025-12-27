package net.shasankp000.GameAI.skills;

import net.minecraft.server.command.ServerCommandSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SkillContext {
    private final ServerCommandSource botSource;
    private final ServerCommandSource requestSource;
    private final Map<String, Object> sharedState;
    private final Map<String, Object> parameters;

    public SkillContext(ServerCommandSource botSource, Map<String, Object> sharedState) {
        this(botSource, sharedState, Collections.emptyMap(), null);
    }

    public SkillContext(ServerCommandSource botSource, Map<String, Object> sharedState, Map<String, Object> parameters) {
        this(botSource, sharedState, parameters, null);
    }

    public SkillContext(ServerCommandSource botSource,
                        Map<String, Object> sharedState,
                        Map<String, Object> parameters,
                        ServerCommandSource requestSource) {
        this.botSource = Objects.requireNonNull(botSource, "botSource");
        this.requestSource = requestSource;
        this.sharedState = sharedState;
        this.parameters = parameters == null ? Collections.emptyMap() : new HashMap<>(parameters);
    }

    public ServerCommandSource botSource() {
        return botSource;
    }

    public ServerCommandSource requestSource() {
        return requestSource;
    }

    public Map<String, Object> sharedState() {
        return sharedState;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}
