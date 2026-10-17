package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterScopePolicyTest {

    private static final String HERE = "Nirn#b5d170e5";

    @Test
    void threeSavedOneHereSplitsOneAndTwo() {
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(List.of("Alex", "Bob", "Jake"),
                Set.of("bob"), Set.of("alex", "bob", "jake"), true);
        assertEquals(List.of("Bob"), scope.thisWorld());
        assertEquals(List.of("Alex", "Jake"), scope.otherWorlds());
    }

    @Test
    void unknownWorldKeyKeepsEveryoneInThisWorld() {
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(List.of("Alex", "Bob", "Jake"),
                Set.of(), Set.of("alex", "bob", "jake"), false);
        assertEquals(List.of("Alex", "Bob", "Jake"), scope.thisWorld());
        assertTrue(scope.otherWorlds().isEmpty());
    }

    @Test
    void emptyAndNullInputsGiveEmptyScope() {
        RosterScopePolicy.Scope empty = RosterScopePolicy.scope(List.of(), Set.of(), Set.of(), true);
        assertTrue(empty.thisWorld().isEmpty());
        assertTrue(empty.otherWorlds().isEmpty());
        RosterScopePolicy.Scope nulls = RosterScopePolicy.scope(null, null, null, true);
        assertTrue(nulls.thisWorld().isEmpty());
        assertTrue(nulls.otherWorlds().isEmpty());
    }

    @Test
    void aliasWithNoWorldRecordAnywhereStaysInThisWorld() {
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(List.of("Fresh", "Old"),
                Set.of(), Set.of("old"), true);
        assertEquals(List.of("Fresh"), scope.thisWorld());
        assertEquals(List.of("Old"), scope.otherWorlds());
    }

    @Test
    void membershipIgnoresCaseAndSurroundingSpace() {
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(List.of(" BoB ", "ALEX"),
                Set.of("bob"), Set.of("bob", "alex"), true);
        assertEquals(List.of(" BoB "), scope.thisWorld());
        assertEquals(List.of("ALEX"), scope.otherWorlds());
    }

    @Test
    void blankAliasesAreDropped() {
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(java.util.Arrays.asList("Bob", " ", null),
                Set.of("bob"), Set.of("bob"), true);
        assertEquals(List.of("Bob"), scope.thisWorld());
        assertTrue(scope.otherWorlds().isEmpty());
    }

    @Test
    void currentWorldKeyMatchesFingerprintOrLegacyLevelName() {
        assertTrue(RosterScopePolicy.isCurrentWorldKey(HERE, HERE, "Nirn"));
        assertTrue(RosterScopePolicy.isCurrentWorldKey("Nirn", HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey("Nirn#0000beef", HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey("test 1#b5d170e5", HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey(" ", HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey("", HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey(null, HERE, "Nirn"));
        assertFalse(RosterScopePolicy.isCurrentWorldKey("Nirn", HERE, " "));
    }

    @Test
    void blankKeyIsNotAWorld() {
        assertTrue(RosterScopePolicy.isUnscopedKey(""));
        assertTrue(RosterScopePolicy.isUnscopedKey(" "));
        assertTrue(RosterScopePolicy.isUnscopedKey(null));
        assertFalse(RosterScopePolicy.isUnscopedKey(HERE));
    }
}
