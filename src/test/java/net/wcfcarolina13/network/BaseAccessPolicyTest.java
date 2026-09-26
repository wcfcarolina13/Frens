package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.network.BaseAccessPolicy.EntryKind.*;
import static org.junit.jupiter.api.Assertions.*;

class BaseAccessPolicyTest {
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
