package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.network.BaseAccessPolicy.EntryKind.*;
import static org.junit.jupiter.api.Assertions.*;

class BaseAccessPolicyTest {
    @Test
    void unownedWallClaimsRequireBuilderOwnershipOrAdministration() {
        assertFalse(BaseAccessPolicy.canClaimUnownedWall(false, false, false));
        assertTrue(BaseAccessPolicy.canClaimUnownedWall(true, false, false));
        assertTrue(BaseAccessPolicy.canClaimUnownedWall(false, true, false));
        assertTrue(BaseAccessPolicy.canClaimUnownedWall(false, false, true));
    }

    @Test
    void zoneEditsRequireOwnerOperatorOrHost() {
        assertTrue(BaseAccessPolicy.canEdit(ZONE, "alice", "alice", false, false));
        assertFalse(BaseAccessPolicy.canEdit(ZONE, "bob", "alice", false, false));
        assertFalse(BaseAccessPolicy.canEdit(ZONE, "bob", null, false, false));
        assertTrue(BaseAccessPolicy.canEdit(ZONE, "bob", null, true, false));
        assertTrue(BaseAccessPolicy.canEdit(ZONE, "bob", null, false, true));
    }

    @Test
    void baseAutoZonesCannotBeDetachedByTheirOwner() {
        assertEquals(BaseAccessPolicy.ZoneEditDecision.BASE_AUTO_ZONE,
                BaseAccessPolicy.zoneEditDecision("base:home", true, "alice", "alice", false, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.ALLOW,
                BaseAccessPolicy.zoneEditDecision("base:home", true, "bob", "alice", true, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.ALLOW,
                BaseAccessPolicy.zoneEditDecision("base:home", true, "bob", "alice", false, true));
    }

    @Test
    void hiddenZonesAlwaysAnswerNotFoundBeforeOtherPermissions() {
        assertEquals(BaseAccessPolicy.ZoneEditDecision.NOT_FOUND,
                BaseAccessPolicy.zoneEditDecision("base:hidden", false, "alice", "alice", false, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.NOT_FOUND,
                BaseAccessPolicy.zoneEditDecision("base:hidden", false, "bob", "alice", true, true));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.NOT_FOUND,
                BaseAccessPolicy.zoneEditDecision("hidden", false, "bob", null, false, false));
    }

    @Test
    void ordinaryZoneEditsKeepOwnershipAndNullOwnerRules() {
        assertEquals(BaseAccessPolicy.ZoneEditDecision.ALLOW,
                BaseAccessPolicy.zoneEditDecision("garden", true, "alice", "alice", false, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.NOT_OWNER,
                BaseAccessPolicy.zoneEditDecision("garden", true, "bob", "alice", false, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.NOT_OWNER,
                BaseAccessPolicy.zoneEditDecision("garden", true, "alice", null, false, false));
        assertEquals(BaseAccessPolicy.ZoneEditDecision.ALLOW,
                BaseAccessPolicy.zoneEditDecision("garden", true, "alice", null, true, false));
    }

    @Test
    void mappingNeedsABotOwnerOperatorOrHost() {
        assertTrue(BaseAccessPolicy.canMapVillage(true, false, false));
        assertFalse(BaseAccessPolicy.canMapVillage(false, false, false));
        assertTrue(BaseAccessPolicy.canMapVillage(false, true, false));
        assertTrue(BaseAccessPolicy.canMapVillage(false, false, true));
    }

    @Test
    void wallWithoutOwnerOrBuilderNeedsOperatorOrHost() {
        assertFalse(BaseAccessPolicy.canEdit(WALL, "alice", null, false, false));
        assertFalse(BaseAccessPolicy.canEdit(WALL, "alice", "", false, false));
        assertTrue(BaseAccessPolicy.canEdit(WALL, "alice", null, true, false));
        assertTrue(BaseAccessPolicy.canEdit(WALL, "alice", null, false, true));
    }

    @Test
    void editsRequireTheResolvedEntryOwnerOrAdministrator() {
        assertTrue(BaseAccessPolicy.canEdit(BASE, "alice", "alice", false, false));
        assertTrue(BaseAccessPolicy.canEdit(WALL, "alice", "alice", false, false));
        assertFalse(BaseAccessPolicy.canEdit(BASE, "bob", "alice", false, false));
        assertFalse(BaseAccessPolicy.canEdit(WALL, "bob", "alice", false, false));
        assertFalse(BaseAccessPolicy.canEdit(WALL, "bob", null, false, false));
        assertFalse(BaseAccessPolicy.canEdit(BASE, "bob", "SERVER", false, false));
        assertFalse(BaseAccessPolicy.canEdit(VILLAGE, "alice", null, false, false));
        assertTrue(BaseAccessPolicy.canEdit(VILLAGE, "alice", null, true, false));
        assertTrue(BaseAccessPolicy.canEdit(VILLAGE, "alice", null, false, true));
        assertTrue(BaseAccessPolicy.canEdit(BASE, "bob", "alice", false, true));
        assertFalse(BaseAccessPolicy.canEdit(MISSING, "alice", "alice", true, true));
    }

    @Test
    void baseVisibilityUsesBaseOwnershipIncludingServerSentinel() {
        assertTrue(BaseAccessPolicy.baseVisible(false, false, "alice", "alice", false, false));
        assertTrue(BaseAccessPolicy.baseVisible(false, false, "bob", "alice", false, true));
        assertTrue(BaseAccessPolicy.baseVisible(false, false, "bob", "SERVER", true, false));
        assertFalse(BaseAccessPolicy.baseVisible(false, false, "bob", "alice", false, false));
        assertFalse(BaseAccessPolicy.baseVisible(false, false, "bob", null, false, false));
        assertTrue(BaseAccessPolicy.baseVisible(true, false, "bob", null, false, false));
        assertTrue(BaseAccessPolicy.baseVisible(false, true, "bob", null, false, false));
    }

    @Test
    void subscriptionCapAllowsExistingViewButRejectsNewOverLimit() {
        assertTrue(BaseAccessPolicy.canSubscribe(15, false, 16));
        assertFalse(BaseAccessPolicy.canSubscribe(16, false, 16));
        assertTrue(BaseAccessPolicy.canSubscribe(16, true, 16));
    }
}
