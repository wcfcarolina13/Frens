package net.wcfcarolina13.network;

import net.wcfcarolina13.network.FortifyWallEditPolicy.WallRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
