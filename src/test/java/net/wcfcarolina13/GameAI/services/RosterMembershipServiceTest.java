package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RosterMembershipServiceTest {

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void nullConfigListsNothing() {
        assertEquals(List.of(), RosterMembershipService.savedAliases(null));
    }

    @Test
    void unionsStoresDropsDefaultAndDuplicatesAndSorts() throws Exception {
        ManualConfig config = newRealConfig();
        config.setBotGameProfile(Map.of("Zed", "{}", "default", "{}"));
        config.setOwner("ava", new ManualConfig.BotOwnership("u", "Owner"));
        config.setOwner("Bob", new ManualConfig.BotOwnership("u", "Owner"));
        List<String> saved = RosterMembershipService.savedAliases(config);
        assertEquals(3, saved.size(), saved.toString());
        assertEquals("ava", saved.get(0).toLowerCase());
        assertEquals("Bob", saved.get(1));
        assertEquals("Zed", saved.get(2));
    }

    @Test
    void preferredSpellingKeepsFirstUnlessItIsTheLowerCasedOne() {
        assertEquals("Abby", RosterMembershipService.preferredSpelling("abby", "Abby"));
        assertEquals("Abby", RosterMembershipService.preferredSpelling("Abby", "abby"));
        assertEquals("AbBy", RosterMembershipService.preferredSpelling("AbBy", "ABBY"));
    }
}
