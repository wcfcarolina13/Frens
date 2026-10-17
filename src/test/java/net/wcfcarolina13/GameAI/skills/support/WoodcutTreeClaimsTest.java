package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.skills.support.WoodcutTreeClaims.TreeKey;
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
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final TreeKey TREE_A = new TreeKey(OVERWORLD, 10, 64, 20);
    private static final TreeKey TREE_B = new TreeKey(OVERWORLD, -40, 70, 5);
    private static final long RUN_1 = 1L;
    private static final long RUN_2 = 2L;
    private final UUID alex = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private static Set<TreeKey> allOthers(WoodcutTreeClaims claims, UUID bot, long nowMs) {
        return claims.claimedByOthersNear(bot, OVERWORLD, 0, 0, -1, nowMs);
    }

    @Test
    void freeTreeIsClaimedAndReclaimedByItsOwner() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL));
        assertEquals(alex, claims.ownerOf(TREE_A, 1_000L));
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 1_000L, TTL));
    }

    @Test
    void anotherBotLosesALiveClaim() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL));
        assertFalse(claims.tryClaim(TREE_A, bob, RUN_2, TTL - 1, TTL));
        assertEquals(alex, claims.ownerOf(TREE_A, TTL - 1));
    }

    @Test
    void expiredClaimCanBeTakenAndTheOldOwnersRefreshThenFails() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL));
        assertNull(claims.ownerOf(TREE_A, TTL));
        assertTrue(claims.tryClaim(TREE_A, bob, RUN_2, TTL, TTL));
        assertEquals(bob, claims.ownerOf(TREE_A, TTL + 1));
        // Alex's refresh after the takeover fails: the caller abandons the tree.
        assertFalse(claims.tryClaim(TREE_A, alex, RUN_1, TTL + 2, TTL));
    }

    @Test
    void refreshExtendsTheExpiry() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL));
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 50_000L, TTL));
        assertFalse(claims.tryClaim(TREE_A, bob, RUN_2, 100_000L, TTL));
        assertTrue(claims.tryClaim(TREE_A, bob, RUN_2, 110_000L, TTL));
    }

    @Test
    void onlyTheOwningRunReleases() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL);
        assertFalse(claims.release(TREE_A, bob, RUN_1));
        assertFalse(claims.release(TREE_A, alex, RUN_2));
        assertEquals(alex, claims.ownerOf(TREE_A, 1L));
        assertTrue(claims.release(TREE_A, alex, RUN_1));
        assertNull(claims.ownerOf(TREE_A, 1L));
        assertTrue(claims.tryClaim(TREE_A, bob, RUN_2, 1L, TTL));
        assertFalse(claims.release(TREE_B, bob, RUN_2));
    }

    @Test
    void releaseAllDropsOnlyThatRunsClaims() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL);
        claims.tryClaim(TREE_B, bob, RUN_2, 0L, TTL);
        claims.releaseAll(alex, RUN_1);
        assertNull(claims.ownerOf(TREE_A, 1L));
        assertEquals(bob, claims.ownerOf(TREE_B, 1L));
    }

    @Test
    void anOldRunsFinallyKeepsTheRestartedRunsClaim() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        // Run 1 claimed A; the bot restarts and run 2 claims B and re-takes A before run 1 unwinds.
        claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL);
        claims.tryClaim(TREE_B, alex, RUN_2, 1L, TTL);
        claims.tryClaim(TREE_A, alex, RUN_2, 2L, TTL);
        claims.releaseAll(alex, RUN_1);
        assertEquals(alex, claims.ownerOf(TREE_A, 3L));
        assertEquals(alex, claims.ownerOf(TREE_B, 3L));
        claims.releaseAll(alex, RUN_2);
        assertNull(claims.ownerOf(TREE_A, 3L));
        assertNull(claims.ownerOf(TREE_B, 3L));
    }

    @Test
    void exclusionSetHoldsOtherBotsLiveClaimsOnly() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL);
        claims.tryClaim(TREE_B, bob, RUN_2, 0L, 10_000L);
        assertEquals(Set.of(TREE_B), allOthers(claims, alex, 5_000L));
        assertEquals(Set.of(TREE_A), allOthers(claims, bob, 5_000L));
        // Bob's short claim expired: Alex no longer avoids that tree.
        assertEquals(Set.of(), allOthers(claims, alex, 10_000L));
    }

    @Test
    void sameCoordsInAnotherDimensionAreAnotherTree() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        TreeKey netherTwin = new TreeKey(NETHER, TREE_A.x(), TREE_A.y(), TREE_A.z());
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 0L, TTL));
        assertTrue(claims.tryClaim(netherTwin, bob, RUN_2, 0L, TTL));
        assertEquals(Set.of(TREE_A), claims.claimedByOthersNear(bob, OVERWORLD, 10, 20, 20, 1L));
        assertEquals(Set.of(netherTwin), claims.claimedByOthersNear(alex, NETHER, 10, 20, 20, 1L));
    }

    @Test
    void nearbyFilterDropsFarClaims() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        claims.tryClaim(TREE_A, bob, RUN_2, 0L, TTL);   // (10, 20)
        claims.tryClaim(TREE_B, bob, RUN_2, 0L, TTL);   // (-40, 5)
        // Alex at (0, 0) with search radius 12 + 8.
        assertEquals(Set.of(TREE_A), claims.claimedByOthersNear(alex, OVERWORLD, 0, 0, 20, 1L));
        assertEquals(Set.of(TREE_A, TREE_B), claims.claimedByOthersNear(alex, OVERWORLD, 0, 0, 40, 1L));
        assertEquals(Set.of(), claims.claimedByOthersNear(alex, OVERWORLD, 300, 300, 20, 1L));
    }

    @Test
    void claimNearRuleIsSameDimensionAndSquareDistance() {
        assertTrue(WoodcutTreeClaims.isClaimNear(OVERWORLD, 20, -20, OVERWORLD, 0, 0, 20));
        assertFalse(WoodcutTreeClaims.isClaimNear(OVERWORLD, 21, 0, OVERWORLD, 0, 0, 20));
        assertFalse(WoodcutTreeClaims.isClaimNear(OVERWORLD, 0, -21, OVERWORLD, 0, 0, 20));
        assertFalse(WoodcutTreeClaims.isClaimNear(NETHER, 0, 0, OVERWORLD, 0, 0, 20));
        assertFalse(WoodcutTreeClaims.isClaimNear(null, 0, 0, OVERWORLD, 0, 0, 20));
        // Negative radius: any distance, still the same dimension only.
        assertTrue(WoodcutTreeClaims.isClaimNear(OVERWORLD, 5_000, 5_000, OVERWORLD, 0, 0, -1));
        assertFalse(WoodcutTreeClaims.isClaimNear(NETHER, 5_000, 5_000, OVERWORLD, 0, 0, -1));
    }

    @Test
    void movedBaseWithinTwoBlocksCountsAsTheClaimedTree() {
        // Claimed base (100, 200); after the bottom log is cut the trunk reads from a new base.
        assertTrue(WoodcutTreeClaims.withinXZ(100, 200, 100, 200, 2));
        assertTrue(WoodcutTreeClaims.withinXZ(101, 201, 100, 200, 2));   // other column of a 2x2
        assertTrue(WoodcutTreeClaims.withinXZ(102, 198, 100, 200, 2));
        assertFalse(WoodcutTreeClaims.withinXZ(103, 200, 100, 200, 2));
        assertFalse(WoodcutTreeClaims.withinXZ(100, 197, 100, 200, 2));
    }

    @Test
    void nullBotOrTreeNeverClaims() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertFalse(claims.tryClaim(TREE_A, null, RUN_1, 0L, TTL));
        assertFalse(claims.tryClaim(null, alex, RUN_1, 0L, TTL));
        assertNull(claims.ownerOf(TREE_A, 0L));
    }
    // Field bug G: a claim keyed on the exact base let a second bot claim the same trunk one block up.

    @Test
    void sameColumnOneBlockUpConflicts() {
        assertTrue(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 10, 65, 70, 20));
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 70, alex, RUN_1, 0L, TTL));
        TreeKey cutTrunk = new TreeKey(OVERWORLD, 10, 65, 20);
        assertFalse(claims.tryClaim(cutTrunk, 70, bob, RUN_2, 1L, TTL));
        assertNull(claims.ownerOf(cutTrunk, 1L));
        assertEquals(alex, claims.ownerOfNear(cutTrunk, 70, bob, 1L));
    }

    @Test
    void otherColumnOfATwoByTwoTrunkConflicts() {
        assertTrue(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 80, 20, OVERWORLD, 11, 64, 80, 21));
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 80, alex, RUN_1, 0L, TTL));
        assertFalse(claims.tryClaim(new TreeKey(OVERWORLD, 11, 64, 20), 80, bob, RUN_2, 1L, TTL));
    }

    @Test
    void separateTreeThreeBlocksAwayIsAllowed() {
        assertFalse(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 13, 64, 70, 20));
        // The gate and the scan's moved-base skip agree on the square boundary.
        assertTrue(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 12, 64, 70, 18));
        assertTrue(WoodcutTreeClaims.isClaimNear(OVERWORLD, 10, 20, OVERWORLD, 12, 18, WoodcutTreeClaims.NEAR_BASE_RADIUS));
        assertFalse(WoodcutTreeClaims.isClaimNear(OVERWORLD, 10, 20, OVERWORLD, 13, 20, WoodcutTreeClaims.NEAR_BASE_RADIUS));
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 70, alex, RUN_1, 0L, TTL));
        TreeKey neighbour = new TreeKey(OVERWORLD, 13, 64, 20);
        assertTrue(claims.tryClaim(neighbour, 70, bob, RUN_2, 1L, TTL));
        assertNull(claims.ownerOfNear(new TreeKey(OVERWORLD, 16, 64, 20), 70, alex, 1L));
    }

    @Test
    void treeOnATerraceAboveOrBelowTheClaimedLogsIsAllowed() {
        // Claimed 64..70, widened to 63..71.
        assertTrue(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 11, 71, 76, 20));
        assertFalse(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 11, 72, 78, 20));
        assertTrue(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 11, 58, 63, 20));
        assertFalse(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, OVERWORLD, 11, 56, 62, 20));
    }

    @Test
    void otherDimensionIsAllowed() {
        assertFalse(WoodcutTreeClaims.conflicts(OVERWORLD, 10, 64, 70, 20, NETHER, 10, 65, 70, 20));
        assertFalse(WoodcutTreeClaims.conflicts(null, 10, 64, 70, 20, OVERWORLD, 10, 65, 70, 20));
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 70, alex, RUN_1, 0L, TTL));
        assertTrue(claims.tryClaim(new TreeKey(NETHER, 10, 65, 20), 70, bob, RUN_2, 1L, TTL));
    }

    @Test
    void expiredNearClaimIsAllowed() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 70, alex, RUN_1, 0L, TTL));
        TreeKey cutTrunk = new TreeKey(OVERWORLD, 10, 65, 20);
        assertFalse(claims.tryClaim(cutTrunk, 70, bob, RUN_2, TTL - 1, TTL));
        assertTrue(claims.tryClaim(cutTrunk, 70, bob, RUN_2, TTL, TTL));
        // Alex's refresh of its expired claim now meets Bob's live near claim: lost.
        assertFalse(claims.tryClaim(TREE_A, alex, RUN_1, TTL + 1, TTL));
        assertEquals(bob, claims.ownerOfNear(TREE_A, 64, alex, TTL + 1));
    }

    @Test
    void sameOwnerReclaimsNearItsOwnClaimAndRefreshKeepsTheTop() {
        WoodcutTreeClaims claims = new WoodcutTreeClaims();
        assertTrue(claims.tryClaim(TREE_A, 70, alex, RUN_1, 0L, TTL));
        assertTrue(claims.tryClaim(new TreeKey(OVERWORLD, 10, 65, 20), 70, alex, RUN_1, 1L, TTL));
        // A top-less refresh keeps 70, so Bob's claim at 70 still conflicts after it.
        assertTrue(claims.tryClaim(TREE_A, alex, RUN_1, 2L, TTL));
        assertFalse(claims.tryClaim(new TreeKey(OVERWORLD, 11, 70, 20), 72, bob, RUN_2, 3L, TTL));
        assertNull(claims.ownerOfNear(TREE_A, 70, alex, 3L));
    }
}
