package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;

import java.util.Optional;

/** Pure decision table: does this committed reply get voiced, and in which playback mode. */
public final class SoulVoiceGate {

    public enum Mode { POSITIONAL, RADIO }

    private SoulVoiceGate() {
    }

    public static Optional<Mode> decide(boolean voiceEnabled, boolean settingsValid,
                                         boolean engineAlive, SoulTypes.Reachability reachability) {
        if (!voiceEnabled || !settingsValid || !engineAlive || reachability == null) {
            return Optional.empty();
        }
        return switch (reachability) {
            case LOCAL -> Optional.of(Mode.POSITIONAL);
            case REMOTE -> Optional.of(Mode.RADIO);
            case UNREACHABLE -> Optional.empty();
        };
    }
}
