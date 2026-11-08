package net.wcfcarolina13.GameAI.souls;

import java.util.Objects;

/**
 * Scene smoothing step 2: where a group prompt marks a scene boundary in the replayed party
 * history.
 *
 * <p>Banter seeds never replay, so earlier ambient scenes reach the model as one unbroken run of
 * companion lines, and a new cue reads as the next turn of the same chat. The model then tends to
 * open with a reply to the last line ("Yeah, ..."). The marker separates scenes. A marker before
 * the cue is used only for narrator-cued scenes (banter, work): a player's follow-up question
 * continues the exchange before it.
 */
public final class SceneBoundaryPolicy {

    /** Fixed text, no interpolation. */
    public static final String MARKER = "[Scene break. A separate, later moment begins. Do not reply to the lines above.]";

    private SceneBoundaryPolicy() {
    }

    /** Whether a break goes between two consecutive replayed records (scene ids, e.g. correlation ids). */
    public static boolean breakBetween(Object previousSceneId, Object nextSceneId) {
        return previousSceneId != null && nextSceneId != null && !Objects.equals(previousSceneId, nextSceneId);
    }

    /** Whether a break goes after the replayed history, just before this scene's cue. */
    public static boolean breakBeforeCue(SoulGroupTypes.SceneKind kind, boolean historyEmpty) {
        if (historyEmpty || kind == null) {
            return false;
        }
        return kind == SoulGroupTypes.SceneKind.BANTER || kind == SoulGroupTypes.SceneKind.WORK;
    }
}
