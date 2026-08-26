package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks in the party-channel key convention and the {@link SoulGroupTypes.GroupSceneTurn}
 * boundary record's defensive rules (same discipline as {@link SoulTypes}).
 */
class SoulGroupTypesTest {

    private static SoulTypes.GroundingSnapshot grounding(UUID botId) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, "Jake", "overworld", "plains",
                0, 64, 0, true, "day", "clear", 20f, 20f, 20, 0, "", 0, 36,
                List.of(), "", "", "", "", "", "Bradley", true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), Instant.EPOCH);
    }

    @Test
    void partyKeyPutsOwnerInBothSlotsOnPartyChannel() {
        UUID owner = UUID.randomUUID();
        SoulTypes.ConversationKey key = SoulGroupTypes.partyKey(owner);
        assertEquals(owner, key.botId());
        assertEquals(owner, key.playerId());
        assertEquals(SoulTypes.Channel.PARTY, key.channel());
    }

    @Test
    void partyCursorKeyIsChannelScopedToTheOwner() {
        UUID owner = UUID.randomUUID();
        assertEquals("PARTY:" + owner, SoulStore.cursorKey(SoulGroupTypes.partyKey(owner)));
    }

    @Test
    void groupSceneTurnDefensivelyCopiesRosterAndCollapsesNulls() {
        UUID owner = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>();
        roster.add(new SoulGroupTypes.SceneParticipant(botId, "frens:jake", "Jake", grounding(botId)));
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                owner, null, roster, null, Instant.EPOCH, UUID.randomUUID());
        roster.clear();
        assertEquals(1, turn.roster().size());
        assertEquals("", turn.ownerDisplayName());
        assertEquals("", turn.playerMessage());
        assertEquals(SoulGroupTypes.partyKey(owner), turn.key());
    }

    @Test
    void sceneParticipantRequiresIdsAndGrounding() {
        assertThrows(NullPointerException.class,
                () -> new SoulGroupTypes.SceneParticipant(null, "p", "n", grounding(UUID.randomUUID())));
        assertThrows(NullPointerException.class,
                () -> new SoulGroupTypes.SceneParticipant(UUID.randomUUID(), "p", "n", null));
    }
}
