package net.wcfcarolina13.network;

import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure decisions behind the per-bot persona and voice-preset pickers (the AI setup checklist's
 * "Pick…" button, actions {@code SET_BOT_PERSONA} and {@code SET_BOT_VOICE}): the wire format
 * of the action's arg, payload validation, which presets an engine offers, and the chat lines.
 * No Minecraft types, so all of it is unit-tested.
 *
 * <p>Who may act is decided by {@link AiSetupActionPolicy#gate}: a persona needs the bot's
 * owner, an operator or the host (as {@code ENABLE_BOT} does); a voice needs an operator or the
 * host, as {@code /bot soul voice assign} does, because the assignment is server-wide config.
 */
public final class BotSoulPickerPolicy {

    private BotSoulPickerPolicy() {
    }

    /** Separates the bot UUID from the chosen value in the action arg. */
    public static final char SEPARATOR = '|';
    /** UUID text is always 36 characters. */
    public static final int UUID_LENGTH = 36;
    /** Longest persona id or preset name accepted. */
    public static final int MAX_VALUE_LENGTH = 64;
    /** Longest arg accepted: UUID, separator, value. Well under the payload's 256-char cap. */
    public static final int MAX_ARG_LENGTH = UUID_LENGTH + 1 + MAX_VALUE_LENGTH;
    /** Voice value that clears the bot's assignment (what {@code voice assign <bot> default} does). */
    public static final String DEFAULT_VOICE = "default";
    /** Most presets carried in the status JSON and offered by the picker. */
    public static final int MAX_PRESETS = 48;
    /** Most personas carried in the status JSON. */
    public static final int MAX_PERSONAS = 32;

    /** One parsed arg: the bot's UUID text (validated) and the value. */
    public record BotChoice(String botUuid, String value) {
    }

    public static String encodeArg(String botUuid, String value) {
        return (botUuid == null ? "" : botUuid) + SEPARATOR + (value == null ? "" : value);
    }

    /**
     * {@code "<uuid>|<value>"}: at most {@link #MAX_ARG_LENGTH} chars, exactly one separator, a
     * parseable 36-char UUID, and a {@link #isSafeValue safe} value. Anything else is empty.
     */
    public static Optional<BotChoice> parseArg(String arg) {
        if (arg == null || arg.length() > MAX_ARG_LENGTH) {
            return Optional.empty();
        }
        int sep = arg.indexOf(SEPARATOR);
        if (sep != UUID_LENGTH || arg.indexOf(SEPARATOR, sep + 1) >= 0) {
            return Optional.empty();
        }
        String uuid = arg.substring(0, sep);
        String value = arg.substring(sep + 1);
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!isSafeValue(value)) {
            return Optional.empty();
        }
        return Optional.of(new BotChoice(uuid, value));
    }

    /** The bot UUID text of a bot-scoped action's arg: the arg itself for ENABLE_BOT, else its first part. */
    public static String botUuidOf(AiSetupActionPolicy.Action action, String arg) {
        if (action == AiSetupActionPolicy.Action.ENABLE_BOT) {
            return arg == null ? "" : arg;
        }
        return parseArg(arg).map(BotChoice::botUuid).orElse("");
    }

    /** 1..{@value #MAX_VALUE_LENGTH} chars of {@code [A-Za-z0-9._:-]}: profile ids and preset names. */
    public static boolean isSafeValue(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ── Persona ─────────────────────────────────────────────────────────────

    public enum PersonaCheck { OK, NOT_ENABLED, UNKNOWN_PERSONA, UNCHANGED }

    /**
     * SET_BOT_PERSONA. The bot must already be bound (enabled once), so a pick never creates soul
     * state for a bot that has none. The persona must be registered. Once made, the pick is kept
     * by later enables and the legacy migration ({@code PersonaBindingPolicy}).
     */
    public static PersonaCheck checkPersona(String requested, Collection<String> registeredIds,
                                            String currentProfileId) {
        if (requested == null || registeredIds == null || !registeredIds.contains(requested)) {
            return PersonaCheck.UNKNOWN_PERSONA;
        }
        if (currentProfileId == null || currentProfileId.isBlank()) {
            return PersonaCheck.NOT_ENABLED;
        }
        return requested.equals(currentProfileId) ? PersonaCheck.UNCHANGED : PersonaCheck.OK;
    }

    public static String personaProblem(PersonaCheck check, String botName) {
        return switch (check) {
            case NOT_ENABLED -> "Enable AI chat for " + botName + " first, then pick a persona.";
            case UNKNOWN_PERSONA -> "That persona isn't registered on the server.";
            case UNCHANGED -> botName + " already speaks as that persona.";
            case OK -> "";
        };
    }

    public static String personaMessage(String botName, String personaName) {
        return botName + " now speaks as " + personaName + ". Their memories are kept.";
    }

    // ── Voice presets ───────────────────────────────────────────────────────

    /**
     * Presets the picker offers under {@code engine}: every Pocket preset; for Piper the voices
     * installed on the server machine (what {@code voice assign} accepts); none for Dreamsleeve,
     * which clones a clip instead. Unsafe names are dropped; at most {@link #MAX_PRESETS}.
     */
    public static List<String> presetsFor(String engine, Collection<String> pocketPresets,
                                          Collection<String> installedPiperVoices) {
        Collection<String> source;
        if (SoulVoiceSettings.ENGINE_POCKET.equals(engine)) {
            source = pocketPresets;
        } else if (SoulVoiceSettings.ENGINE_PIPER.equals(engine)) {
            source = installedPiperVoices;
        } else {
            source = null;
        }
        List<String> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (String name : source) {
            if (out.size() >= MAX_PRESETS) {
                break;
            }
            if (isSafeValue(name) && !DEFAULT_VOICE.equalsIgnoreCase(name) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    public enum VoiceCheck { OK, CLEAR, NO_PRESETS, UNKNOWN_PRESET }

    /** The verdict and, for OK, the preset name as the server lists it. */
    public record VoiceDecision(VoiceCheck check, String preset) {
    }

    /**
     * SET_BOT_VOICE: {@link #DEFAULT_VOICE} clears the assignment; any other value must be one of
     * {@code presets} (case-insensitive, as {@code VoiceCatalog.isKnown} matches).
     */
    public static VoiceDecision checkVoice(String requested, List<String> presets) {
        if (DEFAULT_VOICE.equals(requested)) {
            return new VoiceDecision(VoiceCheck.CLEAR, "");
        }
        if (presets == null || presets.isEmpty()) {
            return new VoiceDecision(VoiceCheck.NO_PRESETS, "");
        }
        if (requested != null) {
            for (String preset : presets) {
                if (preset.equalsIgnoreCase(requested)) {
                    return new VoiceDecision(VoiceCheck.OK, preset);
                }
            }
        }
        return new VoiceDecision(VoiceCheck.UNKNOWN_PRESET, "");
    }

    public static String voiceProblem(VoiceCheck check) {
        return switch (check) {
            case NO_PRESETS -> "The server's voice engine has no presets to pick. "
                    + "Dreamsleeve clones a clip: /bot soul voice assign <bot> clone <ref.wav>.";
            case UNKNOWN_PRESET -> "That voice isn't a preset the server's voice engine has.";
            case OK, CLEAR -> "";
        };
    }

    public static String voiceMessage(String botName, VoiceDecision decision) {
        if (decision.check() == VoiceCheck.CLEAR) {
            return botName + " now uses the default voice.";
        }
        return botName + " → voice " + decision.preset() + ". Takes effect on the next spoken line.";
    }

    // ── Picker screen rows ──────────────────────────────────────────────────

    /** One picker button: the value it sends, its label, whether it is the bot's current pick, and clickable. */
    public record Option(String value, String label, boolean current, boolean enabled) {
    }

    /** Persona buttons: clickable once the bot is bound, except the persona it already has. */
    public static List<Option> personaOptions(List<AiSetupStatus.Persona> personas, String currentProfileId) {
        boolean bound = currentProfileId != null && !currentProfileId.isBlank();
        List<Option> out = new ArrayList<>();
        if (personas == null) {
            return out;
        }
        for (AiSetupStatus.Persona persona : personas) {
            boolean current = persona.id().equals(currentProfileId);
            out.add(new Option(persona.id(), persona.name(), current, bound && !current));
        }
        return out;
    }

    /**
     * Voice buttons: "Default" then each preset, for an operator or host ({@code canAssign}).
     * The current pick is matched case-insensitively; no assignment means Default is current.
     */
    public static List<Option> voiceOptions(List<String> presets, String currentVoice, boolean canAssign) {
        List<Option> out = new ArrayList<>();
        if (!canAssign) {
            return out;
        }
        String cur = currentVoice == null ? "" : currentVoice;
        out.add(new Option(DEFAULT_VOICE, "Default", cur.isEmpty(), !cur.isEmpty()));
        if (presets != null) {
            for (String preset : presets) {
                boolean current = preset.equalsIgnoreCase(cur);
                out.add(new Option(preset, preset, current, !current));
            }
        }
        return out;
    }

    /** The line under the Persona heading, or "" when the buttons say enough. */
    public static String personaNote(boolean serverOffersPersonas, String currentProfileId) {
        if (!serverOffersPersonas) {
            return "This server doesn't offer the persona picker.";
        }
        if (currentProfileId == null || currentProfileId.isBlank()) {
            return "Enable AI chat for this bot first.";
        }
        return "";
    }

    /** The line under the Voice heading, or "" when the buttons say enough. */
    public static String voiceNote(boolean canAssign, int presetCount) {
        if (!canAssign) {
            return "Ask the server operator / host.";
        }
        if (presetCount == 0) {
            return "No presets for the server's voice engine.";
        }
        return "";
    }
}
