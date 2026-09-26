package net.wcfcarolina13.network;

import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pure decisions behind {@link AiSetupNetworkManager}'s action packets: who may do what, which
 * model tags and engine ids are acceptable, the throttle/cache arithmetic, and the
 * prerequisite-aware "bot enabled" message shared with {@code /bot soul enable}. No Minecraft
 * types, so all of it is unit-tested.
 */
public final class AiSetupActionPolicy {

    private AiSetupActionPolicy() {
    }

    /** Client-requested setup actions. Wire names are the enum names. */
    public enum Action {
        SOULS_ON, SOULS_OFF, ENABLE_BOT, SELECT_MODEL, SELECT_VOICE_ENGINE;

        public static Optional<Action> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            for (Action a : values()) {
                if (a.name().equals(raw)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }

        /** Every action except ENABLE_BOT changes server-wide config: op or host only. */
        public boolean changesServerSettings() {
            return this != ENABLE_BOT;
        }
    }

    public enum Verdict { ALLOW, DENY_NOT_OPERATOR, DENY_NO_SUCH_BOT, DENY_NOT_OWNER }

    /**
     * Who may do {@code action}. Server-wide actions need {@code canEditServer} (operator or
     * integrated host). ENABLE_BOT needs a registered, online fake bot ({@code botIsRegistered})
     * that the sender owns or may administer ({@code botAccessAllowed}: owner, op or host, as
     * {@code BotAccessGate.decide} computes it).
     */
    public static Verdict gate(Action action, boolean canEditServer, boolean botIsRegistered,
                               boolean botAccessAllowed) {
        if (action == null) {
            return Verdict.DENY_NOT_OPERATOR;
        }
        if (action.changesServerSettings()) {
            return canEditServer ? Verdict.ALLOW : Verdict.DENY_NOT_OPERATOR;
        }
        if (!botIsRegistered) {
            return Verdict.DENY_NO_SUCH_BOT;
        }
        return botAccessAllowed ? Verdict.ALLOW : Verdict.DENY_NOT_OWNER;
    }

    /** Player-visible reason for a denied action. */
    public static String denialMessage(Verdict verdict) {
        return switch (verdict) {
            case DENY_NOT_OPERATOR -> "Only an operator or the host can change the server's AI settings.";
            case DENY_NO_SUCH_BOT -> "That bot isn't online or isn't a Frens bot.";
            case DENY_NOT_OWNER -> "Only the bot's owner, an operator or the host can enable AI chat for it.";
            case ALLOW -> "";
        };
    }

    // ── Model tags ──────────────────────────────────────────────────────────

    public static final int MAX_TAG_LENGTH = 64;

    public enum TagCheck { OK, BLANK, TOO_LONG, BAD_CHARS, NOT_AVAILABLE }

    /** Length and charset only: 1..64 chars of {@code [A-Za-z0-9._:/-]}, no trimming. */
    public static TagCheck checkTagSyntax(String tag) {
        if (tag == null || tag.isEmpty()) {
            return TagCheck.BLANK;
        }
        if (tag.length() > MAX_TAG_LENGTH) {
            return TagCheck.TOO_LONG;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '/' || c == '-';
            if (!ok) {
                return TagCheck.BAD_CHARS;
            }
        }
        return TagCheck.OK;
    }

    /**
     * Full SELECT_MODEL check: valid syntax, and the tag is in the Frens catalog or among the
     * server's installed Ollama tags. {@code installedTags} null means "unknown" (Ollama not
     * probed or unreachable) — then only catalog tags pass.
     */
    public static TagCheck checkModelTag(String tag, Collection<String> catalogTags,
                                         Collection<String> installedTags) {
        TagCheck syntax = checkTagSyntax(tag);
        if (syntax != TagCheck.OK) {
            return syntax;
        }
        if (catalogTags != null && catalogTags.contains(tag)) {
            return TagCheck.OK;
        }
        if (installedTags != null && installedTags.contains(tag)) {
            return TagCheck.OK;
        }
        return TagCheck.NOT_AVAILABLE;
    }

    /** Whether validating {@code tag} needs the server's installed-tag list (not a catalog tag). */
    public static boolean needsInstalledTags(String tag, Collection<String> catalogTags) {
        return checkTagSyntax(tag) == TagCheck.OK && (catalogTags == null || !catalogTags.contains(tag));
    }

    public static String tagProblem(TagCheck check) {
        return switch (check) {
            case BLANK -> "No model was chosen.";
            case TOO_LONG -> "That model name is too long (at most " + MAX_TAG_LENGTH + " characters).";
            case BAD_CHARS -> "That model name has characters Ollama tags never use.";
            case NOT_AVAILABLE -> "That model isn't in the Frens list or installed in Ollama on the server machine.";
            case OK -> "";
        };
    }

    // ── Voice engines ───────────────────────────────────────────────────────

    public static final List<String> VOICE_ENGINES = List.of(
            SoulVoiceSettings.ENGINE_PIPER, SoulVoiceSettings.ENGINE_POCKET, SoulVoiceSettings.ENGINE_DREAMSLEEVE);

    public static boolean isKnownEngine(String id) {
        return id != null && VOICE_ENGINES.contains(id);
    }

    // ── Throttle / cache arithmetic ─────────────────────────────────────────

    /** Per-player status requests: at most one per this many ms. */
    public static final long STATUS_REQUEST_INTERVAL_MS = 2_000L;
    /** Per-player actions: at most one per this many ms. */
    public static final long ACTION_INTERVAL_MS = 1_000L;
    /** How long one Ollama probe result is reused server-wide. */
    public static final long OLLAMA_PROBE_TTL_MS = 10_000L;

    /** True when a request at {@code nowMs} is allowed after one at {@code lastMs} (null = never). */
    public static boolean throttleAllows(Long lastMs, long nowMs, long intervalMs) {
        return lastMs == null || nowMs - lastMs >= intervalMs || nowMs < lastMs;
    }

    /** True when a result produced at {@code producedAtMs} may still be reused at {@code nowMs}. */
    public static boolean cacheFresh(long producedAtMs, long nowMs, long ttlMs) {
        return producedAtMs > 0 && nowMs >= producedAtMs && nowMs - producedAtMs < ttlMs;
    }

    // ── "/bot soul enable" result text ──────────────────────────────────────

    /**
     * The message after a bot was bound and activated, saying what still stands between the
     * player and a reply, first problem first: Soul Chat off, then invalid settings (e.g. no
     * model), then Ollama not answering. {@code providerHealthy} null means "not checked" and
     * never produces the Ollama branch.
     */
    public static String enableMessage(String botName, String personaName, boolean fallbackPersona,
                                       boolean soulsEnabled, String validationError, Boolean providerHealthy) {
        String prefix = botName + " will speak as " + personaName + ", but ";
        if (!soulsEnabled) {
            return prefix + "Soul Chat is OFF. Turn it on in Set up AI companions or run /bot soul system on.";
        }
        if (validationError != null && !validationError.isBlank()) {
            return prefix + botName + " can't reply yet: " + validationError;
        }
        if (Boolean.FALSE.equals(providerHealthy)) {
            return prefix + "Ollama isn't answering on the server machine.";
        }
        return botName + " is now speaking as " + personaName
                + (fallbackPersona ? " (no profile of their own is registered yet)." : ".");
    }

    /** Whether {@code botName} got the Jake fallback rather than a persona of its own. */
    public static boolean isFallbackPersona(String botName, String boundProfileId, String jakeProfileId) {
        return boundProfileId != null && boundProfileId.equals(jakeProfileId)
                && (botName == null || !botName.equalsIgnoreCase("Jake"));
    }
}
