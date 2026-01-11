package net.shasankp000.GameAI.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal quest schema model (seed-agnostic).
 *
 * <p>Intentionally tiny and implementation-oriented. Avoids branching, dialogue trees,
 * and hard dependencies.
 */
public final class QuestDefinition {

    public String quest_id = "";
    public String category = ""; // exploration | combat | curiosity | bonding
    public String intent = "";

    public Constraints constraints = new Constraints();

    public List<Action> actions = new ArrayList<>();

    /**
     * Currently unused by the runtime; kept for forward compatibility with the guide.
     */
    public List<String> completion_conditions = new ArrayList<>();

    public Reflection bot_reflection = new Reflection();

    public String idOrEmpty() {
        return quest_id != null ? quest_id : "";
    }

    public boolean isValid() {
        if (quest_id == null || quest_id.isBlank()) {
            return false;
        }
        if (actions == null || actions.isEmpty()) {
            return false;
        }
        // By design: 1–3 actions.
        return actions.size() <= 3;
    }

    public static final class Constraints {
        public List<String> environment_predicates = new ArrayList<>();
        public List<String> entity_predicates = new ArrayList<>();
        public List<String> state_predicates = new ArrayList<>();

        public List<String> env() {
            return environment_predicates != null ? environment_predicates : List.of();
        }

        public List<String> entities() {
            return entity_predicates != null ? entity_predicates : List.of();
        }

        public List<String> state() {
            return state_predicates != null ? state_predicates : List.of();
        }
    }

    public static final class Action {
        public String type = ""; // move | observe | fight | collect | wait
        public Map<String, Object> parameters;

        public String typeOrEmpty() {
            return type != null ? type : "";
        }

        public Map<String, Object> params() {
            return parameters != null ? parameters : Map.of();
        }
    }

    public static final class Reflection {
        public String on_success = "";
        public String on_failure = "";

        public String successOrEmpty() {
            return on_success != null ? on_success : "";
        }

        public String failureOrEmpty() {
            return on_failure != null ? on_failure : "";
        }
    }
}
