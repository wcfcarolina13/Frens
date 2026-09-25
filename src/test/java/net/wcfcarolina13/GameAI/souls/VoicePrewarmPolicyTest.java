package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePrewarmPolicyTest {

    private static VoicePrewarmPolicy.Candidate bot(String name, String profile) {
        return new VoicePrewarmPolicy.Candidate(name, profile, true, true);
    }

    @Test
    void selectsOnlineActiveBotsInCandidateOrder() {
        List<SoulTypes.VoiceKey> keys = VoicePrewarmPolicy.select(
                List.of(bot("Jake", "frens:jake"), bot("Bob", "frens:bob")), Set.of());
        assertEquals(List.of(new SoulTypes.VoiceKey("Jake", "frens:jake"),
                new SoulTypes.VoiceKey("Bob", "frens:bob")), keys);
    }

    @Test
    void skipsOfflineInactiveAndBlankProfileCandidates() {
        List<SoulTypes.VoiceKey> keys = VoicePrewarmPolicy.select(List.of(
                new VoicePrewarmPolicy.Candidate("Gone", "frens:jake", false, true),
                new VoicePrewarmPolicy.Candidate("Muted", "frens:bob", true, false),
                new VoicePrewarmPolicy.Candidate("Unbound", "  ", true, true),
                bot("Silas", "frens:silas")), Set.of());
        assertEquals(List.of(new SoulTypes.VoiceKey("Silas", "frens:silas")), keys);
    }

    @Test
    void dedupesIdenticalKeysButKeepsDistinctBotsOnOneProfile() {
        // The resolver checks a bot-name assignment before the profile's, so two bots sharing a
        // profile can need different voices — both keys must survive; exact repeats must not.
        List<SoulTypes.VoiceKey> keys = VoicePrewarmPolicy.select(List.of(
                bot("Jake", "frens:jake"), bot(" Jake ", "frens:jake"), bot("Jake2", "frens:jake")),
                Set.of());
        assertEquals(List.of(new SoulTypes.VoiceKey("Jake", "frens:jake"),
                new SoulTypes.VoiceKey("Jake2", "frens:jake")), keys);
    }

    @Test
    void skipsKeysAlreadyWarmed() {
        List<SoulTypes.VoiceKey> keys = VoicePrewarmPolicy.select(
                List.of(bot("Jake", "frens:jake"), bot("Bob", "frens:bob")),
                Set.of(new SoulTypes.VoiceKey("Jake", "frens:jake")));
        assertEquals(List.of(new SoulTypes.VoiceKey("Bob", "frens:bob")), keys);
    }

    @Test
    void emptyOrNullInputsSelectNothing() {
        assertTrue(VoicePrewarmPolicy.select(List.of(), Set.of()).isEmpty());
        assertTrue(VoicePrewarmPolicy.select(null, Set.of()).isEmpty());
        List<VoicePrewarmPolicy.Candidate> withNull = new ArrayList<>(Arrays.asList(null, bot("Bob", "frens:bob")));
        assertEquals(List.of(new SoulTypes.VoiceKey("Bob", "frens:bob")),
                VoicePrewarmPolicy.select(withNull, null));
    }
}
