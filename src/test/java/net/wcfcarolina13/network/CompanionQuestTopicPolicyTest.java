package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionQuestTopicPolicyTest {

    @Test
    void theScreensRealRequestsPass() {
        assertTrue(CompanionQuestTopicPolicy.refusal("Steve", "companion_status").isEmpty());
        assertTrue(CompanionQuestTopicPolicy.refusal("Bob the Bot", "topic_nether_fortress_memory").isEmpty());
        assertTrue(CompanionQuestTopicPolicy.refusal("a".repeat(BotActionPolicy.MAX_ALIAS_LEN), "batch3_travel").isEmpty());
        assertTrue(CompanionQuestTopicPolicy.refusal("Steve", "k".repeat(CompanionQuestTopicPolicy.MAX_TOPIC_KEY_LEN)).isEmpty());
    }

    @Test
    void anUnboundedOrUnsafeAliasIsRefusedBeforeAnyLookup() {
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal(null, "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("", "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("   ", "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("x".repeat(32767), "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("a".repeat(BotActionPolicy.MAX_ALIAS_LEN + 1), "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("Steve§k", "companion_status"));
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("all", "companion_status"));
    }

    @Test
    void anUnboundedTopicKeyIsRefused() {
        assertEquals(Optional.of("BAD_TOPIC"), CompanionQuestTopicPolicy.refusal("Steve", null));
        assertEquals(Optional.of("BAD_TOPIC"), CompanionQuestTopicPolicy.refusal("Steve", " "));
        assertEquals(Optional.of("BAD_TOPIC"), CompanionQuestTopicPolicy.refusal("Steve", "k".repeat(CompanionQuestTopicPolicy.MAX_TOPIC_KEY_LEN + 1)));
    }

    @Test
    void theAliasIsCheckedFirst() {
        assertEquals(Optional.of("BAD_ALIAS"), CompanionQuestTopicPolicy.refusal("", ""));
    }
}
