package net.wcfcarolina13.GameAI.souls;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure selection rule for TTS pre-warm (1.1.217): which voices should have their engine started
 * before the first soul scene, given the bots currently around. A candidate qualifies when it is
 * online and has an active soul profile — the same check scene rosters use — and its voice key
 * has not been warmed yet for the current voice service.
 *
 * <p>Keys are the full {@code (botName, profileId)} pair, exactly what synthesis passes: the
 * voice resolver consults a bot-name assignment before the profile's, so two bots sharing a
 * profile can legitimately need different engine processes. Engines dedupe further on their own
 * process identity. No game classes, no I/O, no state.
 */
final class VoicePrewarmPolicy {

    private VoicePrewarmPolicy() {
    }

    /** One bot as seen by the pre-warm sweep. {@code botName} must be the bot's display name. */
    record Candidate(String botName, String profileId, boolean online, boolean activeProfile) {
        Candidate {
            botName = botName == null ? "" : botName.trim();
            profileId = profileId == null ? "" : profileId.trim();
        }
    }

    /**
     * Voice keys to warm, in candidate order, without duplicates. Skips offline bots, bots
     * without an active profile, blank profile ids, and keys in {@code alreadyWarmed}.
     */
    static List<SoulTypes.VoiceKey> select(List<Candidate> candidates,
                                           Set<SoulTypes.VoiceKey> alreadyWarmed) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<SoulTypes.VoiceKey> picked = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || !candidate.online() || !candidate.activeProfile()
                    || candidate.profileId().isEmpty()) {
                continue;
            }
            SoulTypes.VoiceKey key = new SoulTypes.VoiceKey(candidate.botName(), candidate.profileId());
            if (alreadyWarmed != null && alreadyWarmed.contains(key)) {
                continue;
            }
            picked.add(key);
        }
        return List.copyOf(picked);
    }
}
