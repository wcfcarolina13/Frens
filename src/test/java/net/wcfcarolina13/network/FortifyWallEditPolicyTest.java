package net.wcfcarolina13.network;

import net.wcfcarolina13.network.FortifyWallEditPolicy.WallRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.wcfcarolina13.network.FortifyWallEditPolicy.Use;

import net.wcfcarolina13.network.FortifyWallEditPolicy.Refusal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortifyWallEditPolicyTest {

    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    @Test
    void noWallFoundIsAllowedBecauseTheSkillRefusesOrBuildsNew() {
        assertTrue(FortifyWallEditPolicy.mayEdit(false, null, ME, false, false));
    }

    @Test
    void ownWallIsAllowed() {
        assertTrue(FortifyWallEditPolicy.mayEdit(true, ME, ME, false, false));
    }

    @Test
    void someoneElsesWallIsRefused() {
        assertFalse(FortifyWallEditPolicy.mayEdit(true, OTHER, ME, false, false));
    }

    @Test
    void legacyAndServerWallsAreRefusedForNonAdmins() {
        assertFalse(FortifyWallEditPolicy.mayEdit(true, "", ME, false, false));
        assertFalse(FortifyWallEditPolicy.mayEdit(true, "SERVER", ME, false, false));
    }

    @Test
    void operatorAndHostMayEditAnyWall() {
        assertTrue(FortifyWallEditPolicy.mayEdit(true, OTHER, ME, true, false));
        assertTrue(FortifyWallEditPolicy.mayEdit(true, "", ME, false, true));
    }

    @Test
    void nearestOfNothingIsNull() {
        assertNull(FortifyWallEditPolicy.nearest(List.of(), 0, 0));
        assertNull(FortifyWallEditPolicy.nearest(null, 0, 0));
    }

    @Test
    void nearestUsesXzDistance() {
        List<WallRef> walls = List.of(
                new WallRef("far", OTHER, 100, 100),
                new WallRef("near", ME, 10, -5));
        assertEquals("near", FortifyWallEditPolicy.nearest(walls, 0, 0).name());
    }

    @Test
    void nearestTieGoesToTheFirstListed() {
        List<WallRef> walls = new ArrayList<>();
        walls.add(new WallRef("first", OTHER, 10, 0));
        walls.add(null);
        walls.add(new WallRef("second", ME, -10, 0));
        assertEquals("first", FortifyWallEditPolicy.nearest(walls, 0, 0).name());
    }

    @Test
    void blankLabelChecksTheNearestWallsOwner() {
        List<WallRef> walls = List.of(
                new WallRef("mine", ME, 200, 0),
                new WallRef("theirs", OTHER, 5, 0));
        WallRef picked = FortifyWallEditPolicy.nearest(walls, 0, 0);
        assertFalse(FortifyWallEditPolicy.mayEdit(picked != null, picked.ownerUuid(), ME, false, false));
    }

    @Test
    void viewIsAllowedForWallsTheBaseScreenShows() {
        assertTrue(FortifyWallEditPolicy.mayUse(Use.VIEW, true, OTHER, true, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.VIEW, true, ME, true, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.VIEW, true, OTHER, false, false, ME, true, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.VIEW, true, OTHER, false, false, ME, false, true));
    }

    @Test
    void viewIsRefusedForHiddenWalls() {
        assertFalse(FortifyWallEditPolicy.mayUse(Use.VIEW, true, OTHER, false, false, ME, false, false));
        assertFalse(FortifyWallEditPolicy.mayUse(Use.VIEW, true, "", false, false, ME, false, false));
    }

    @Test
    void noWallFoundIsAllowedForBothUses() {
        assertTrue(FortifyWallEditPolicy.mayUse(Use.VIEW, false, null, false, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.EDIT, false, null, false, false, ME, false, false));
    }

    @Test
    void editNeedsOwnershipEvenWhenVisible() {
        // An ally sees the wall but may not edit it.
        assertFalse(FortifyWallEditPolicy.mayUse(Use.EDIT, true, OTHER, true, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.EDIT, true, ME, true, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.EDIT, true, OTHER, false, false, ME, true, false));
    }

    @Test
    void rewrittenTokensMatchTheSkillParser() {
        for (String t : List.of("auto", "AUTO", "Auto", "ascent", "ascend", "descent", "descend",
                "ascent-y", "DESCENT-Y", "2", "-7", "+3", "x=1", "a=", "=")) {
            assertTrue(FortifyWallEditPolicy.isRewrittenToken(t), t);
        }
        for (String t : List.of("autumn", "wall_-12_40", "North", "W1", "2a", "9999999999")) {
            assertFalse(FortifyWallEditPolicy.isRewrittenToken(t), t);
        }
    }

    @Test
    void labelReachesTheSkillOnlyWhenNoTokenIsDroppedOrMoved() {
        Set<String> blocks = Set.of("stone", "oak_log");
        assertTrue(FortifyWallEditPolicy.labelReachesSkillIntact("North Wall", blocks::contains));
        assertFalse(FortifyWallEditPolicy.labelReachesSkillIntact("stone wall", blocks::contains));
        assertFalse(FortifyWallEditPolicy.labelReachesSkillIntact("theirwall auto", blocks::contains));
        assertFalse(FortifyWallEditPolicy.labelReachesSkillIntact("wall 2", blocks::contains));
        assertFalse(FortifyWallEditPolicy.labelReachesSkillIntact("", blocks::contains));
        assertTrue(FortifyWallEditPolicy.labelReachesSkillIntact("stone wall", null));
    }

    @Test
    void granteeMayWorkOnTheWallButNotChangeItsHull() {
        // D1: Grant Wall Access gives resume/patch/patch_auto/moat, not expand.
        assertTrue(FortifyWallEditPolicy.mayUse(Use.WORK, true, OTHER, true, true, ME, false, false));
        assertFalse(FortifyWallEditPolicy.mayUse(Use.EDIT, true, OTHER, true, true, ME, false, false));
        // Not granted: an ally still may not work on the wall.
        assertFalse(FortifyWallEditPolicy.mayUse(Use.WORK, true, OTHER, true, false, ME, false, false));
        // Owner, op and host work on it without a grant.
        assertTrue(FortifyWallEditPolicy.mayUse(Use.WORK, true, ME, true, false, ME, false, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.WORK, true, OTHER, false, false, ME, true, false));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.WORK, true, "", false, false, ME, false, true));
        assertTrue(FortifyWallEditPolicy.mayUse(Use.WORK, false, null, false, false, ME, false, false));
    }

    @Test
    void staleGrantOnAnOwnerlessWallDoesNotCount() {
        // Unclaim keeps the granted list, so a grant only counts on a wall with a player owner.
        assertFalse(FortifyWallEditPolicy.mayUse(Use.WORK, true, "", true, true, ME, false, false));
        assertFalse(FortifyWallEditPolicy.mayUse(Use.WORK, true, null, true, true, ME, false, false));
        assertFalse(FortifyWallEditPolicy.mayUse(Use.WORK, true, "SERVER", true, true, ME, false, false));
    }

    @Test
    void useForSubSplitsExpandFromTheWorkSubs() {
        assertEquals(Use.WORK, FortifyWallEditPolicy.useForSub("resume"));
        assertEquals(Use.WORK, FortifyWallEditPolicy.useForSub("patch"));
        assertEquals(Use.WORK, FortifyWallEditPolicy.useForSub("patch_auto"));
        assertEquals(Use.WORK, FortifyWallEditPolicy.useForSub("moat"));
        assertEquals(Use.EDIT, FortifyWallEditPolicy.useForSub("expand"));
        assertEquals(Use.VIEW, FortifyWallEditPolicy.useForSub("status"));
        assertEquals(Use.VIEW, FortifyWallEditPolicy.useForSub("drift"));
        assertNull(FortifyWallEditPolicy.useForSub("start"));
        assertNull(FortifyWallEditPolicy.useForSub("list"));
        assertNull(FortifyWallEditPolicy.useForSub(null));
        // Same subs as the caller's edit/read sets, so nothing is checked under the wrong rule.
        Set<String> union = new java.util.HashSet<>(FortifyWallEditPolicy.WORK_SUBS);
        union.addAll(FortifyWallEditPolicy.EDIT_SUBS);
        assertEquals(BotActionPolicy.FORTIFY_WALL_EDIT_SUBS, union);
        assertEquals(BotActionPolicy.FORTIFY_WALL_VIEW_SUBS, FortifyWallEditPolicy.VIEW_SUBS);
    }

    @Test
    void refusalNamesLegacyOwnerlessWalls() {
        assertEquals(Refusal.NONE, FortifyWallEditPolicy.refusal(Use.EDIT, true, ME, true, false, ME, false, false));
        assertEquals(Refusal.LEGACY_OWNERLESS, FortifyWallEditPolicy.refusal(Use.EDIT, true, "", false, false, ME, false, false));
        assertEquals(Refusal.LEGACY_OWNERLESS, FortifyWallEditPolicy.refusal(Use.WORK, true, null, false, true, ME, false, false));
        assertEquals(Refusal.LEGACY_OWNERLESS, FortifyWallEditPolicy.refusal(Use.VIEW, true, "", false, false, ME, false, false));
        assertEquals(Refusal.NOT_PERMITTED, FortifyWallEditPolicy.refusal(Use.EDIT, true, OTHER, true, true, ME, false, false));
        assertEquals(Refusal.NOT_PERMITTED, FortifyWallEditPolicy.refusal(Use.WORK, true, "SERVER", false, false, ME, false, false));
        assertEquals(Refusal.NONE, FortifyWallEditPolicy.refusal(Use.EDIT, true, "", false, false, ME, false, true));
    }

    @Test
    void refusalMessages() {
        assertEquals("That wall has no owner. Claim it in the Base screen first.",
                FortifyWallEditPolicy.refusalMessage(Refusal.LEGACY_OWNERLESS));
        assertEquals("You cannot work on that wall.", FortifyWallEditPolicy.refusalMessage(Refusal.NOT_PERMITTED));
        for (Refusal r : Refusal.values()) {
            String m = FortifyWallEditPolicy.refusalMessage(r);
            assertFalse(m.contains(";") || m.contains("\u2014"), m);
        }
    }
}
