package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.DeathRetrievalPolicy.ReturnMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathDropFilterPolicyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void freshDropAtTheBodyIsADeathDrop() {
        assertTrue(DeathDropFilterPolicy.isDeathDrop(0, 0.0, 1.32, 0.0));
        assertTrue(DeathDropFilterPolicy.isDeathDrop(1, -1.5, 0.0, 1.9));
    }

    @Test
    void oldOrFarItemsAreNotDeathDrops() {
        assertFalse(DeathDropFilterPolicy.isDeathDrop(2, 0.0, 1.0, 0.0));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(400, 0.0, 1.0, 0.0));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(0, 2.5, 1.0, 0.0));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(0, 0.0, 1.0, -2.5));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(0, 0.0, -1.5, 0.0));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(0, 0.0, 3.5, 0.0));
        assertFalse(DeathDropFilterPolicy.isDeathDrop(-1, 0.0, 1.0, 0.0));
    }

    @Test
    void noFilterAllowsEverything() {
        assertTrue(DeathDropFilterPolicy.allowed(null, A));
        assertTrue(DeathDropFilterPolicy.allowed(Set.of(), A));
        assertTrue(DeathDropFilterPolicy.allowed(null, null));
    }

    @Test
    void filterAllowsOnlyTaggedItems() {
        assertTrue(DeathDropFilterPolicy.allowed(Set.of(A), A));
        assertFalse(DeathDropFilterPolicy.allowed(Set.of(A), B));
        assertFalse(DeathDropFilterPolicy.allowed(Set.of(A), null));
    }

    @Test
    void toFilterSkipsMalformedAndEmpty() {
        assertNull(DeathDropFilterPolicy.toFilter(null));
        assertNull(DeathDropFilterPolicy.toFilter(List.of()));
        assertNull(DeathDropFilterPolicy.toFilter(Arrays.asList("nope", null)));
        assertEquals(Set.of(A, B), DeathDropFilterPolicy.toFilter(Arrays.asList(A.toString(), "x", null, B.toString())));
    }

    @Test
    void deathMustBeInTheMirroredStandBox() {
        assertTrue(DeathDropFilterPolicy.deathNearStand(0, 0, 0));
        assertTrue(DeathDropFilterPolicy.deathNearStand(-3, 2, 3));
        assertTrue(DeathDropFilterPolicy.deathNearStand(3, -2, -3));
        assertFalse(DeathDropFilterPolicy.deathNearStand(4, 0, 0));
        assertFalse(DeathDropFilterPolicy.deathNearStand(0, 3, 0));
        assertFalse(DeathDropFilterPolicy.deathNearStand(0, -3, 0));
        assertFalse(DeathDropFilterPolicy.deathNearStand(120, 0, -40));
    }

    @Test
    void actionTypeCarriesWhoAndStillParses() {
        String me = DeathRetrievalPolicy.actionType(ReturnMode.SENDER, "me");
        assertEquals("retrieve:sender:me", me);
        assertEquals(Optional.of(ReturnMode.SENDER), DeathRetrievalPolicy.parseAction(me));
        assertEquals(Optional.of("me"), DeathRetrievalPolicy.parseWho(me));
        String bot = DeathRetrievalPolicy.actionType(ReturnMode.STAY, "Smokey");
        assertEquals(Optional.of(ReturnMode.STAY), DeathRetrievalPolicy.parseAction(bot));
        assertEquals(Optional.of("Smokey"), DeathRetrievalPolicy.parseWho(bot));
        assertTrue(PostArrivalPersistPolicy.persistable(bot));
    }

    @Test
    void actionTypeWithoutOrBadWho() {
        assertEquals("retrieve:sender", DeathRetrievalPolicy.actionType(ReturnMode.SENDER, null));
        assertEquals("retrieve:sender", DeathRetrievalPolicy.actionType(ReturnMode.SENDER, "a:b"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseWho("retrieve:sender"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("retrieve:sender:"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("retrieve:sender:a:b"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("retrieve:home:me"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseWho("withdraw:player"));
    }
}
