package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the shared woodcut tree claims. No net.minecraft types. */
class WoodcutTreeClaimsTest {

    private static final long TTL = 60_000L;
    private static final long TREE_A = 123_456L;
    private static final long TREE_B = -987_654L;
    private final UUID alex = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void freeTreeIsClaimedAndReclaimedByItsOwner() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, 0L, TTL));
        assertEquals(alex, claims.ownerOf(TREE_A, 1_000L));
        assertTrue(claims.tryClaim(TREE_A, alex, 1_000L, TTL));
    }

    @Test
    void anotherBotLosesALiveClaim() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, 0L, TTL));
        assertFalse(claims.tryClaim(TREE_A, bob, TTL - 1, TTL));
        assertEquals(alex, claims.ownerOf(TREE_A, TTL - 1));
    }

    @Test
    void expiredClaimCanBeTaken() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, 0L, TTL));
        assertNull(claims.ownerOf(TREE_A, TTL));
        assertTrue(claims.tryClaim(TREE_A, bob, TTL, TTL));
        assertEquals(bob, claims.ownerOf(TREE_A, TTL + 1));
    }

    @Test
    void refreshExtendsTheExpiry() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, 0L, TTL));
        assertTrue(claims.tryClaim(TREE_A, alex, 50_000L, TTL));
        assertFalse(claims.tryClaim(TREE_A, bob, 100_000L, TTL));
        assertTrue(claims.tryClaim(TREE_A, bob, 110_000L, TTL));
    }

    @Test
    void onlyTheOwnerReleases() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, 0L, TTL);
        assertFalse(claims.release(TREE_A, bob));
        assertEquals(alex, claims.ownerOf(TREE_A, 1L));
        assertTrue(claims.release(TREE_A, alex));
        assertNull(claims.ownerOf(TREE_A, 1L));
        assertTrue(claims.tryClaim(TREE_A, bob, 1L, TTL));
        assertFalse(claims.release(TREE_B, bob));
    }

    @Test
    void releaseAllDropsOnlyThatBotsClaims() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, 0L, TTL);
        claims.tryClaim(TREE_B, bob, 0L, TTL);
        claims.releaseAll(alex);
        assertNull(claims.ownerOf(TREE_A, 1L));
        assertEquals(bob, claims.ownerOf(TREE_B, 1L));
    }

    @Test
    void exclusionSetHoldsOtherBotsLiveClaimsOnly() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, 0L, TTL);
        claims.tryClaim(TREE_B, bob, 0L, 10_000L);
        assertEquals(Set.of(TREE_B), claims.claimedByOthers(alex, 5_000L));
        assertEquals(Set.of(TREE_A), claims.claimedByOthers(bob, 5_000L));
        // Bob's short claim expired: Alex no longer avoids that tree.
        assertEquals(Set.of(), claims.claimedByOthers(alex, 10_000L));
    }

    @Test
    void nullBotNeverClaims() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertFalse(claims.tryClaim(TREE_A, null, 0L, TTL));
        assertNull(claims.ownerOf(TREE_A, 0L));
    }
}
