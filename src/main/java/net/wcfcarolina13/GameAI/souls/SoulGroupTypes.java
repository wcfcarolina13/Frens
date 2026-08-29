package net.wcfcarolina13.GameAI.souls;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable boundary model for the group-scene (PARTY channel) extension of the soul domain.
 * Same rules as {@link SoulTypes}: plain data only, no Minecraft/Fabric classes, defensive
 * copies, required identifiers reject {@code null}.
 */
public final class SoulGroupTypes {

    private SoulGroupTypes() {
        // Namespace class — not instantiable.
    }

    /** Most bots one scene will orchestrate; nearest eligible bots win. */
    public static final int MAX_SCENE_BOTS = 4;
    /** Most lines any single bot may speak in one scene. */
    public static final int MAX_LINES_PER_BOT = 2;
    /** Most lines one player-initiated scene may contain in total. */
    public static final int MAX_SCENE_LINES = 6;
    /** Tighter scene cap for autonomous banter scenes. */
    public static final int BANTER_MAX_SCENE_LINES = 4;
    /** Tighter scene cap for a one-bot overheard-chat reaction: exactly one line. */
    public static final int LOCAL_MAX_SCENE_LINES = 1;
    /** Longest single scene line (chars, after cleaning); longer lines are truncated. */
    public static final int MAX_LINE_CHARS = 300;

    /**
     * How a scene came to exist. {@code PLAYER} scenes (broadcast/multi-name chat addresses)
     * keep the soul-DM visibility exemption. {@code BANTER} and {@code LOCAL} scenes are
     * system-initiated and ambient-like: their delivery respects the ambient text/voice category
     * masks, their failures are silent, and combat aborts their remaining lines.
     */
    public enum SceneKind {
        PLAYER, BANTER, LOCAL;

        /** True for the system-initiated kinds that obey the ambient masks. */
        public boolean isAmbient() {
            return this != PLAYER;
        }
    }

    /**
     * The party channel's conversation/store/scheduler key. Deliberately reuses
     * {@link SoulTypes.ConversationKey} with BOTH id slots carrying the owner's UUID: the party
     * store is a separate {@link SoulStore} instance rooted at {@code frens/party/v1}, so the
     * "botId" path segment resolves to the owner's directory there and can never collide with any
     * DM path; the scheduler's per-key single-flight and {@code cancelForPlayer(playerId)}
     * semantics apply unchanged because {@code playerId()} is the owner.
     */
    public static SoulTypes.ConversationKey partyKey(UUID ownerId) {
        return new SoulTypes.ConversationKey(ownerId, ownerId, SoulTypes.Channel.PARTY);
    }

    /** One roster member, grounding captured at accept time (fresh roster per turn). */
    public record SceneParticipant(UUID botId, String profileId, String displayName,
                                    SoulTypes.GroundingSnapshot grounding) {
        public SceneParticipant {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(grounding, "grounding");
            profileId = profileId == null ? "" : profileId;
            displayName = displayName == null ? "" : displayName;
        }
    }

    /**
     * An accepted group turn: N roster bots, one triggering owner, one message. {@code routingId}
     * plays the same correlation role as {@link SoulTypes.AcceptedTurn#routingId()} — minted at
     * the routing surface, adopted end to end, and reused to derive per-line voice group ids.
     */
    public record GroupSceneTurn(SceneKind kind, UUID ownerId, String ownerDisplayName,
                                  List<SceneParticipant> roster, String playerMessage,
                                  Instant acceptedAt, UUID routingId, boolean addressPlayer) {
        public GroupSceneTurn {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(routingId, "routingId");
            ownerDisplayName = ownerDisplayName == null ? "" : ownerDisplayName;
            playerMessage = playerMessage == null ? "" : playerMessage;
            roster = roster == null ? List.of() : List.copyOf(roster);
        }

        /**
         * Pre-engagement shape (1.1.177–1.1.180): the scene never addresses the player.
         * {@code addressPlayer} is only ever set by the banter director's fire-time coin
         * (engagement spec §3) — PLAYER and LOCAL turns always carry {@code false}.
         */
        public GroupSceneTurn(SceneKind kind, UUID ownerId, String ownerDisplayName,
                               List<SceneParticipant> roster, String playerMessage,
                               Instant acceptedAt, UUID routingId) {
            this(kind, ownerId, ownerDisplayName, roster, playerMessage, acceptedAt,
                    routingId, false);
        }

        /** Compatibility shape from the group-chat pilot: a player-initiated scene. */
        public GroupSceneTurn(UUID ownerId, String ownerDisplayName,
                               List<SceneParticipant> roster, String playerMessage,
                               Instant acceptedAt, UUID routingId) {
            this(SceneKind.PLAYER, ownerId, ownerDisplayName, roster, playerMessage,
                    acceptedAt, routingId, false);
        }

        public SoulTypes.ConversationKey key() {
            return partyKey(ownerId);
        }
    }

    /** One validated scene line: which roster member speaks, and the clean dialogue text. */
    public record SceneLine(int participantIndex, String text) {
        public SceneLine {
            text = text == null ? "" : text;
        }
    }
}
