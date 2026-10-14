package net.wcfcarolina13.network;

import net.wcfcarolina13.network.BotActionPolicy.Access;
import net.wcfcarolina13.network.BotActionPolicy.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.wcfcarolina13.network.BotActionPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class BotActionPolicyTest {

    private static final Set<String> KNOWN = Set.of("bob", "jake", "big bob");

    private static String ok(Action action, String bot, String... args) {
        Optional<String> out = BotActionPolicy.build(action, List.of(args), bot, KNOWN);
        assertTrue(out.isPresent(), () -> action + " " + Arrays.toString(args) + " should build");
        return out.get();
    }

    private static void bad(Action action, String bot, String... args) {
        assertEquals(Optional.empty(), BotActionPolicy.build(action, List.of(args), bot, KNOWN),
                () -> action + " " + Arrays.toString(args) + " should be refused");
    }

    // ── Access ──────────────────────────────────────────────────────────────

    @Test
    void spawnIsServerAdminAndEverythingElseIsBotOwner() {
        assertEquals(Access.SERVER_ADMIN, SPAWN.access());
        for (Action a : Action.values()) {
            if (a != SPAWN) assertEquals(Access.BOT_OWNER, a.access(), a.name());
        }
    }

    @Test
    void parseActionAcceptsOnlyExactEnumNames() {
        assertEquals(Optional.of(STOP), BotActionPolicy.parseAction("STOP"));
        assertEquals(Optional.empty(), BotActionPolicy.parseAction("stop"));
        assertEquals(Optional.empty(), BotActionPolicy.parseAction("RECRUIT_RESET"));
        assertEquals(Optional.empty(), BotActionPolicy.parseAction(""));
        assertEquals(Optional.empty(), BotActionPolicy.parseAction(null));
        assertEquals(Optional.empty(), BotActionPolicy.parseAction("X".repeat(40)));
    }

    // ── Canonical strings ───────────────────────────────────────────────────

    @Test
    void noArgActionsPutTheResolvedBotInItsSlot() {
        assertEquals("open Bob", ok(OPEN, "Bob"));
        assertEquals("stop Bob", ok(STOP, "Bob"));
        assertEquals("resume Bob", ok(RESUME, "Bob"));
        assertEquals("standdown Bob", ok(STAND_DOWN, "Bob"));
        assertEquals("come Bob", ok(COME, "Bob"));
        assertEquals("go_to_look Bob", ok(GO_TO_LOOK, "Bob"));
        assertEquals("sleep Bob", ok(SLEEP, "Bob"));
        assertEquals("return Bob", ok(RETURN_HOME, "Bob"));
        assertEquals("follow Bob", ok(FOLLOW, "Bob"));
        assertEquals("follow stop Bob", ok(FOLLOW_STOP, "Bob"));
        assertEquals("follow toggle Bob", ok(FOLLOW_TOGGLE, "Bob"));
        assertEquals("leash Bob", ok(LEASH, "Bob"));
        assertEquals("fish forget Bob", ok(FISH_FORGET, "Bob"));
        for (Action a : List.of(OPEN, STOP, RESUME, STAND_DOWN, COME, GO_TO_LOOK, SLEEP, RETURN_HOME,
                FOLLOW, FOLLOW_STOP, FOLLOW_TOGGLE, LEASH, FISH_FORGET)) {
            bad(a, "Bob", "all");
        }
    }

    @Test
    void stringSlotsQuoteNamesWithSpacesOrApostrophes() {
        assertEquals("stop \"Big Bob\"", ok(STOP, "Big Bob"));
        assertEquals("companion come \"O'Neil\"", ok(COMPANION, "O'Neil", "come"));
        assertEquals("Bob_2.x-y+", BotActionPolicy.quoteIfNeeded("Bob_2.x-y+"));
        assertEquals("\"a\\\"b\\\\c\"", BotActionPolicy.quoteIfNeeded("a\"b\\c"));
        assertEquals("\"\"", BotActionPolicy.quoteIfNeeded(""));
    }

    @Test
    void skillTokenSlotsRefuseNamesWithWhitespace() {
        bad(LEASH, "Big Bob");
        bad(SKILL, "Big Bob", "harvest");
        bad(FORTIFY, "Big Bob", "start", "");
    }

    @Test
    void playerSlotsRefuseNamesLongerThanSixteen() {
        bad(RETURN_HOME, "ABCDEFGHIJKLMNOPQ");
        bad(GUARD, "ABCDEFGHIJKLMNOPQ", "8");
        assertEquals("return ABCDEFGHIJKLMNOP", ok(RETURN_HOME, "ABCDEFGHIJKLMNOP"));
        assertEquals("return \"Big Bob\"", ok(RETURN_HOME, "Big Bob"));
    }

    @Test
    void reservedOrSelectorResolvedNamesAreRefused() {
        bad(STOP, "all");
        bad(STOP, "EACH");
        bad(STOP, "@a");
        bad(STOP, "");
    }

    @Test
    void followDistanceIsOneDecimalInRange() {
        assertEquals("follow-distance 4.0 Bob", ok(FOLLOW_DISTANCE, "Bob", "4"));
        assertEquals("follow-distance 4.5 Bob", ok(FOLLOW_DISTANCE, "Bob", "4.5"));
        assertEquals("follow-distance 1.0 Bob", ok(FOLLOW_DISTANCE, "Bob", "1.0"));
        assertEquals("follow-distance 64.0 Bob", ok(FOLLOW_DISTANCE, "Bob", "64.0"));
        bad(FOLLOW_DISTANCE, "Bob", "0.9");
        bad(FOLLOW_DISTANCE, "Bob", "64.1");
        bad(FOLLOW_DISTANCE, "Bob", "-2");
        bad(FOLLOW_DISTANCE, "Bob", "4,5");
        bad(FOLLOW_DISTANCE, "Bob", "1e1");
        bad(FOLLOW_DISTANCE, "Bob");
    }

    @Test
    void guardAndPatrolBindTheBotBeforeTheRadius() {
        assertEquals("guard Bob 3", ok(GUARD, "Bob", "3"));
        assertEquals("guard Bob 32", ok(GUARD, "Bob", "32"));
        assertEquals("patrol Bob 12", ok(PATROL, "Bob", "12"));
        bad(GUARD, "Bob", "2");
        bad(GUARD, "Bob", "33");
        bad(PATROL, "Bob", "12.5");
        bad(PATROL, "Bob");
    }

    @Test
    void simpleSkillsUseTheAllowlist() {
        for (String s : BotActionPolicy.SIMPLE_SKILLS) {
            assertEquals("skill " + s + " Bob", ok(SKILL, "Bob", s));
        }
        bad(SKILL, "Bob", "fortify_village");
        bad(SKILL, "Bob", "hunt");
        bad(SKILL, "Bob", "drop_sweep all");
        bad(SKILL, "Bob");
    }

    @Test
    void countedSkills() {
        assertEquals("skill woodcut Bob", ok(SKILL_WOODCUT, "Bob"));
        assertEquals("skill woodcut 64 Bob", ok(SKILL_WOODCUT, "Bob", "64"));
        bad(SKILL_WOODCUT, "Bob", "0");
        bad(SKILL_WOODCUT, "Bob", "65");
        assertEquals("skill fish Bob", ok(SKILL_FISH, "Bob"));
        assertEquals("skill fish 128 Bob", ok(SKILL_FISH, "Bob", "128"));
        bad(SKILL_FISH, "Bob", "129");
        bad(SKILL_FISH, "Bob", "jake");

        assertEquals("skill wool Bob", ok(SKILL_WOOL, "Bob", "0", "0"));
        assertEquals("skill wool 12 Bob", ok(SKILL_WOOL, "Bob", "12", "0"));
        assertEquals("skill wool range=16 Bob", ok(SKILL_WOOL, "Bob", "0", "16"));
        assertEquals("skill wool 256 range=128 Bob", ok(SKILL_WOOL, "Bob", "256", "128"));
        bad(SKILL_WOOL, "Bob", "257", "0");
        bad(SKILL_WOOL, "Bob", "1", "15");
        bad(SKILL_WOOL, "Bob", "1", "129");
        bad(SKILL_WOOL, "Bob", "1");

        assertEquals("skill stripmine 1 Bob", ok(SKILL_STRIPMINE, "Bob", "1"));
        assertEquals("skill stripmine 128 Bob", ok(SKILL_STRIPMINE, "Bob", "128"));
        assertEquals("skill stripmine Bob", ok(SKILL_STRIPMINE, "Bob"));
        bad(SKILL_STRIPMINE, "Bob", "0");
        bad(SKILL_STRIPMINE, "Bob", "1", "2");
        assertEquals("skill mining ascent Bob", ok(SKILL_ASCENT, "Bob"));
        assertEquals("skill mining descent Bob", ok(SKILL_DESCENT, "Bob"));

        assertEquals("skill mining ascent 5 Bob", ok(SKILL_ASCENT, "Bob", "5"));
        assertEquals("skill mining ascent surface Bob", ok(SKILL_ASCENT, "Bob", "surface"));
        bad(SKILL_ASCENT, "Bob", "0");
        bad(SKILL_ASCENT, "Bob", "SURFACE");
        assertEquals("skill mining descent 128 Bob", ok(SKILL_DESCENT, "Bob", "128"));
        bad(SKILL_DESCENT, "Bob", "129");
        bad(SKILL_DESCENT, "Bob", "surface");
    }

    @Test
    void placementSkillsTakeCoordinates() {
        assertEquals("skill farm targetX=10 targetY=-60 targetZ=-3 manual=true Bob",
                ok(SKILL_FARM, "Bob", "10", "-60", "-3"));
        bad(SKILL_FARM, "Bob", "10", "2049", "0");
        bad(SKILL_FARM, "Bob", "30000001", "64", "0");
        bad(SKILL_FARM, "Bob", "1.5", "64", "0");
        bad(SKILL_FARM, "Bob", "1", "64");

        assertEquals("skill shelter hovel radius=4 targetX=1 targetY=2 targetZ=3 Bob",
                ok(SKILL_SHELTER, "Bob", "hovel", "1", "2", "3"));
        assertEquals("skill shelter burrow targetX=1 targetY=2 targetZ=3 Bob",
                ok(SKILL_SHELTER, "Bob", "burrow", "1", "2", "3"));
        bad(SKILL_SHELTER, "Bob", "hovel2", "1", "2", "3");

        assertEquals("skill build small_hut targetX=1 targetY=2 targetZ=3 Bob",
                ok(SKILL_BUILD, "Bob", "small_hut", "0", "1", "2", "3"));
        assertEquals("skill build watchtower rotation=3 targetX=1 targetY=2 targetZ=3 Bob",
                ok(SKILL_BUILD, "Bob", "watchtower", "3", "1", "2", "3"));
        for (String s : BotActionPolicy.BUILD_SCHEMATICS) {
            assertTrue(BotActionPolicy.build(SKILL_BUILD, List.of(s, "1", "0", "0", "0"), "Bob", KNOWN).isPresent(), s);
        }
        bad(SKILL_BUILD, "Bob", "watchtower", "4", "1", "2", "3");
        bad(SKILL_BUILD, "Bob", "list", "0", "1", "2", "3");
        bad(SKILL_BUILD, "Bob", "../evil", "0", "1", "2", "3");
    }

    @Test
    void huntValidatesCountAndMobId() {
        assertEquals("skill hunt Bob", ok(SKILL_HUNT, "Bob", "0", ""));
        assertEquals("skill hunt 5 Bob", ok(SKILL_HUNT, "Bob", "5", ""));
        assertEquals("skill hunt 5 cow Bob", ok(SKILL_HUNT, "Bob", "5", "cow"));
        assertEquals("skill hunt mod:deer Bob", ok(SKILL_HUNT, "Bob", "0", "mod:deer"));
        assertEquals("skill hunt 1000 cow Bob", ok(SKILL_HUNT, "Bob", "1000", "cow"));
        assertEquals("skill hunt 99999 Bob", ok(SKILL_HUNT, "Bob", "99999", ""));
        bad(SKILL_HUNT, "Bob", "100000", "cow");
        bad(SKILL_HUNT, "Bob", "-1", "cow");
        bad(SKILL_HUNT, "Bob", "1", "all");
        bad(SKILL_HUNT, "Bob", "1", "each");
        bad(SKILL_HUNT, "Bob", "1", "Jake");
        bad(SKILL_HUNT, "Bob", "1", "cow pig");
        bad(SKILL_HUNT, "Bob", "1", "Cow");
        bad(SKILL_HUNT, "Bob", "1", "12");
        bad(SKILL_HUNT, "Bob", "1", "x".repeat(65));
    }

    @Test
    void fortifyBuildsEverySubcommandWithTheBotLast() {
        assertEquals("fortify Bob", ok(FORTIFY, "Bob", "start", ""));
        assertEquals("fortify list Bob", ok(FORTIFY, "Bob", "list", ""));
        assertEquals("fortify resume Bob", ok(FORTIFY, "Bob", "resume", ""));
        assertEquals("fortify resume North Wall Bob", ok(FORTIFY, "Bob", "resume", "North Wall"));
        assertEquals("fortify patch North Wall Bob", ok(FORTIFY, "Bob", "patch", "North Wall"));
        assertEquals("fortify patch North Wall auto Bob", ok(FORTIFY, "Bob", "patch_auto", "North Wall"));
        assertEquals("fortify status W1 Bob", ok(FORTIFY, "Bob", "status", "W1"));
        assertEquals("fortify moat W1 Bob", ok(FORTIFY, "Bob", "moat", "W1"));
        assertEquals("fortify drift W1 Bob", ok(FORTIFY, "Bob", "drift", "W1"));
        assertEquals("fortify expand W1 Bob", ok(FORTIFY, "Bob", "expand", "W1"));
        bad(FORTIFY, "Bob", "start", "W1");
        bad(FORTIFY, "Bob", "list", "W1");
        bad(FORTIFY, "Bob", "patch_auto", "");
        bad(FORTIFY, "Bob", "name", "a b");
        bad(FORTIFY, "Bob", "resume", "Jake's all");
        bad(FORTIFY, "Bob", "resume", "wall each");
        bad(FORTIFY, "Bob", "resume", "near jake");
        bad(FORTIFY, "Bob", "resume");
        // Words the skill parser or handlePatch would drop from the wall name.
        bad(FORTIFY, "Bob", "patch", "theirwall auto");
        bad(FORTIFY, "Bob", "patch", "theirwall AUTO");
        bad(FORTIFY, "Bob", "patch_auto", "theirwall auto");
        bad(FORTIFY, "Bob", "status", "wall 2");
        bad(FORTIFY, "Bob", "status", "wall +2");
        bad(FORTIFY, "Bob", "drift", "wall x=1");
        bad(FORTIFY, "Bob", "resume", "descent wall");
        bad(FORTIFY, "Bob", "expand", "wall Ascent-Y");
        assertEquals("fortify status wall_-12_40 Bob", ok(FORTIFY, "Bob", "status", "wall_-12_40"));
        assertEquals("fortify status autumn Bob", ok(FORTIFY, "Bob", "status", "autumn"));
        assertTrue(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("patch", "")));
        assertTrue(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("resume", "W1")));
        assertTrue(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("patch_auto", "W1")));
        assertTrue(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("moat", "")));
        assertTrue(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("expand", "")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("start", "")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("list", "")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("status", "W1")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of("drift", "W1")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(SKILL, List.of("patch", "")));
        assertFalse(BotActionPolicy.fortifyEditsSavedWall(FORTIFY, List.of()));
        assertTrue(BotActionPolicy.fortifyReadsSavedWall(FORTIFY, List.of("status", "")));
        assertTrue(BotActionPolicy.fortifyReadsSavedWall(FORTIFY, List.of("drift", "W1")));
        assertFalse(BotActionPolicy.fortifyReadsSavedWall(FORTIFY, List.of("patch", "")));
        assertFalse(BotActionPolicy.fortifyReadsSavedWall(FORTIFY, List.of("list", "")));
        assertFalse(BotActionPolicy.fortifyReadsSavedWall(SKILL, List.of("status", "")));
    }

    @Test
    void fortifyListIsServerAdminOnly() {
        assertTrue(BotActionPolicy.requiresServerAdmin(FORTIFY, List.of("list", "")));
        assertTrue(BotActionPolicy.requiresServerAdmin(SPAWN, List.of()));
        assertFalse(BotActionPolicy.requiresServerAdmin(FORTIFY, List.of("status", "")));
        assertFalse(BotActionPolicy.requiresServerAdmin(FORTIFY, List.of("start", "")));
        assertFalse(BotActionPolicy.requiresServerAdmin(SKILL, List.of("list")));
        assertFalse(BotActionPolicy.requiresServerAdmin(FORTIFY, List.of()));
    }

    @Test
    void blankFortifyWallSubsGetTheResolvedWallName() {
        for (String sub : List.of("resume", "patch", "moat", "expand", "status", "drift")) {
            List<String> named = BotActionPolicy.withResolvedFortifyLabel(FORTIFY, List.of(sub, ""), "wall_1_2");
            assertEquals(List.of(sub, "wall_1_2"), named);
            assertEquals("fortify " + sub + " wall_1_2 Bob",
                    BotActionPolicy.build(FORTIFY, named, "Bob", KNOWN).orElseThrow());
        }
        List<String> labelled = List.of("patch", "W1");
        assertSame(labelled, BotActionPolicy.withResolvedFortifyLabel(FORTIFY, labelled, "wall_1_2"));
        List<String> start = List.of("start", "");
        assertSame(start, BotActionPolicy.withResolvedFortifyLabel(FORTIFY, start, "wall_1_2"));
        List<String> list = List.of("list", "");
        assertSame(list, BotActionPolicy.withResolvedFortifyLabel(FORTIFY, list, "wall_1_2"));
        List<String> blank = List.of("patch", "");
        assertSame(blank, BotActionPolicy.withResolvedFortifyLabel(FORTIFY, blank, null));
        // A stored name the label rules refuse is caught when the named command is built.
        assertEquals(Optional.empty(), BotActionPolicy.build(FORTIFY,
                BotActionPolicy.withResolvedFortifyLabel(FORTIFY, blank, "Bob"), "Bob", KNOWN));
    }

    @Test
    void togglesAndHobbiesUseClosedSets() {
        for (String key : BotActionPolicy.TOGGLE_KEYS) {
            assertEquals(key + " toggle Bob", ok(TOGGLE, "Bob", key, "toggle"));
        }
        assertEquals("idle_hobbies off Bob", ok(TOGGLE, "Bob", "idle_hobbies", "off"));
        bad(TOGGLE, "Bob", "globalTeleport", "toggle");
        bad(TOGGLE, "Bob", "idle_hobbies", "toggle_and_idle");
        assertEquals(10, BotActionPolicy.TOGGLE_KEYS.size());

        for (String h : BotActionPolicy.HOBBY_NAMES) {
            assertEquals("hobby " + h + " on Bob", ok(HOBBY_SET, "Bob", h, "on"));
        }
        assertEquals("hobby walk_dogs off Bob", ok(HOBBY_SET, "Bob", "walk_dogs", "off"));
        bad(HOBBY_SET, "Bob", "dance", "on");
        bad(HOBBY_SET, "Bob", "fish", "toggle");
        assertEquals(16, BotActionPolicy.HOBBY_NAMES.size());
        // One source of truth with the screen handler's bit order (BotPlayerInventoryScreenHandler
        // has Minecraft imports, so both read HobbyBitOrder).
        assertSame(net.wcfcarolina13.ui.HobbyBitOrder.NAMES, BotActionPolicy.HOBBY_NAMES);
        assertEquals(BotActionPolicy.HOBBY_NAMES.size(), new java.util.HashSet<>(BotActionPolicy.HOBBY_NAMES).size());
        assertTrue(BotActionPolicy.HOBBY_NAMES.size() <= 31);
    }

    @Test
    void companionSubcommands() {
        for (String s : BotActionPolicy.COMPANION_SUBS) {
            assertEquals("companion " + s + " Bob", ok(COMPANION, "Bob", s));
        }
        bad(COMPANION, "Bob", "pledge");
    }

    @Test
    void compassPutsTheBotBeforeTheName() {
        assertEquals("compass home Bob Old Mill", ok(COMPASS_HOME, "Bob", "Old Mill"));
        assertEquals("compass home \"Big Bob\" Mill", ok(COMPASS_HOME, "Big Bob", "Mill"));
        assertEquals("compass travel Bob", ok(COMPASS_TRAVEL, "Bob", ""));
        assertEquals("compass travel Bob Old Mill", ok(COMPASS_TRAVEL, "Bob", "Old Mill"));
        bad(COMPASS_HOME, "Bob", "");
        bad(COMPASS_HOME, "Bob", "Mill§c");
        bad(COMPASS_HOME, "Bob", "Mill\nx");
        bad(COMPASS_HOME, "Bob", " Mill");
        bad(COMPASS_HOME, "Bob", "a  b");
        bad(COMPASS_HOME, "Bob", "say \"hi\"");
        bad(COMPASS_HOME, "Bob", "x".repeat(65));
        bad(COMPASS_HOME, null, "Mill");
        bad(COMPASS_TRAVEL, null, "");
        // The bot has its own string() slot before the greedy name, so bot names and the
        // all/each keywords cannot retarget a compass command.
        assertEquals("compass home Bob all stars", ok(COMPASS_HOME, "Bob", "all stars"));
        assertEquals("compass travel Bob Jake each", ok(COMPASS_TRAVEL, "Bob", "Jake each"));
    }

    @Test
    void craftAndCookBindTheBotInItsPlayerSlot() {
        // Amount 1 too: "craft stick Bob" would bind Bob as <material> and craft with the active bot.
        assertEquals("craft stick 1 \"\" Bob", ok(CRAFT, "Bob", "stick", "1"));
        assertEquals("craft stick 4 \"\" Bob", ok(CRAFT, "Bob", "stick", "4"));
        assertEquals("craft \"mod:gear\" 64 \"\" Bob", ok(CRAFT, "Bob", "mod:gear", "64"));
        bad(CRAFT, "Bob", "stick", "0");
        bad(CRAFT, "Bob", "stick", "65");
        bad(CRAFT, "Bob", "Stick", "1");
        bad(CRAFT, "Bob", "all", "1");
        bad(CRAFT, "Bob", "bob", "1");
        bad(CRAFT, "Bob", "stick 4", "1");

        assertEquals("cook beef none Bob", ok(COOK, "Bob", "beef", "none"));
        assertEquals("cook beef auto Bob", ok(COOK, "Bob", "beef", "auto"));
        assertEquals("cook beef coal Bob", ok(COOK, "Bob", "beef", "coal"));
        assertEquals("cook \"mod:meat\" \"mod:fuel\" Bob", ok(COOK, "Bob", "mod:meat", "mod:fuel"));
        bad(COOK, "Bob", "beef", "");
        bad(COOK, "Bob", "beef", "jake");
        bad(COOK, "Bob", "beef");
    }

    @Test
    void spawnTakesTheNewNameOnly() {
        assertEquals("spawn Steve admin", ok(SPAWN, "Steve"));
        assertEquals("spawn \"Old Steve\" admin", ok(SPAWN, "Old Steve"));
        bad(SPAWN, "Steve", "survival");
        bad(SPAWN, "x".repeat(25));
        bad(SPAWN, "St@ve");
        bad(SPAWN, null);
        assertTrue(BotActionPolicy.isValidNewBotName("O'Neil 2.0"));
        assertFalse(BotActionPolicy.isValidNewBotName("all"));
        assertFalse(BotActionPolicy.isValidNewBotName(" Steve"));
    }

    // ── Untargeted (admin, blank alias) ─────────────────────────────────────

    @Test
    void untargetedFormsExistOnlyWhereTheGrammarHasThem() {
        assertEquals("stop", ok(STOP, null));
        assertEquals("return", ok(RETURN_HOME, null));
        assertEquals("guard 8", ok(GUARD, null, "8"));
        assertEquals("skill harvest", ok(SKILL, null, "harvest"));
        assertEquals("fortify patch W1", ok(FORTIFY, null, "patch", "W1"));
        assertEquals("craft stick 4", ok(CRAFT, null, "stick", "4"));
        assertEquals("cook beef none", ok(COOK, null, "beef", "none"));
        assertEquals("idle_hobbies toggle", ok(TOGGLE, null, "idle_hobbies", "toggle"));
        Set<Action> none = EnumSet.of(COMPASS_HOME, COMPASS_TRAVEL, SPAWN);
        for (Action a : Action.values()) {
            assertEquals(!none.contains(a), a.hasUntargetedForm(), a.name());
        }
    }

    // ── Generic guards ──────────────────────────────────────────────────────

    @Test
    void nullOrTooManyArgsAreRefused() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals(Optional.empty(), BotActionPolicy.build(SKILL, withNull, "Bob", KNOWN));
        assertEquals(Optional.empty(), BotActionPolicy.build(STOP, List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), "Bob", KNOWN));
        assertEquals(Optional.empty(), BotActionPolicy.build(null, List.of(), "Bob", KNOWN));
        assertEquals(Optional.of("stop Bob"), BotActionPolicy.build(STOP, null, "Bob", null));
    }

    @Test
    void everyActionBuildsForAValidShape() {
        for (Action a : Action.values()) {
            List<String> args = switch (a) {
                case FOLLOW_DISTANCE -> List.of("3");
                case GUARD, PATROL -> List.of("8");
                case SKILL -> List.of("harvest");
                case SKILL_WOOL -> List.of("0", "0");
                case SKILL_STRIPMINE, SKILL_ASCENT, SKILL_DESCENT -> List.of("4");
                case SKILL_FARM -> List.of("0", "64", "0");
                case SKILL_SHELTER -> List.of("hovel", "0", "64", "0");
                case SKILL_BUILD -> List.of("bridge", "0", "0", "64", "0");
                case SKILL_HUNT -> List.of("0", "");
                case FORTIFY -> List.of("start", "");
                case TOGGLE -> List.of("tactical_shelter", "toggle");
                case HOBBY_SET -> List.of("fish", "on");
                case COMPANION -> List.of("home");
                case COMPASS_HOME -> List.of("Mill");
                case COMPASS_TRAVEL -> List.of("");
                case CRAFT -> List.of("stick", "1");
                case COOK -> List.of("beef", "none");
                default -> List.of();
            };
            assertTrue(BotActionPolicy.build(a, args, "Bob", KNOWN).isPresent(), a.name());
        }
    }

    @Test
    void aliasRules() {
        assertTrue(BotActionPolicy.isValidAlias("Bob"));
        assertTrue(BotActionPolicy.isValidAlias("Big Bob"));
        assertTrue(BotActionPolicy.isValidAlias("O'Neil-2.x_"));
        assertFalse(BotActionPolicy.isValidAlias(""));
        assertFalse(BotActionPolicy.isValidAlias("all"));
        assertFalse(BotActionPolicy.isValidAlias("Each"));
        assertFalse(BotActionPolicy.isValidAlias("@a"));
        assertFalse(BotActionPolicy.isValidAlias("\"Bob\""));
        assertFalse(BotActionPolicy.isValidAlias(" Bob"));
        assertFalse(BotActionPolicy.isValidAlias("Bob;op"));
        assertFalse(BotActionPolicy.isValidAlias("x".repeat(33)));
    }

    // ── Dispatch refusal ────────────────────────────────────────────────────

    @Test
    void dispatchRefusesErrorsUnreadInputCatchAllAndIncomplete() {
        List<String> stop = List.of("bot", "stop", "target");
        assertEquals(Optional.empty(), BotActionPolicy.dispatchRefusal(false, false, true, stop));
        assertEquals(Optional.of("PARSE_ERROR"), BotActionPolicy.dispatchRefusal(true, false, true, stop));
        assertEquals(Optional.of("UNREAD_INPUT"), BotActionPolicy.dispatchRefusal(false, true, true, stop));
        assertEquals(Optional.of("NOT_EXECUTABLE"), BotActionPolicy.dispatchRefusal(false, false, false, stop));
        assertEquals(Optional.of("CATCH_ALL"),
                BotActionPolicy.dispatchRefusal(false, false, true, List.of("bot", "inline")));
        assertEquals(Optional.of("CATCH_ALL"),
                BotActionPolicy.dispatchRefusal(false, false, true, List.of("bot", "inline", "x")));
        assertEquals(Optional.of("EMPTY"), BotActionPolicy.dispatchRefusal(false, false, true, List.of()));
        assertEquals(Optional.of("EMPTY"), BotActionPolicy.dispatchRefusal(false, false, true, null));
        assertEquals(Optional.empty(),
                BotActionPolicy.dispatchRefusal(false, false, true, List.of("bot", "skill", "skill_name", "arguments")));
    }

    // ── Throttle ────────────────────────────────────────────────────────────

    @Test
    void throttleAllowsABurstThenTenPerSecond() {
        BotActionPolicy.Throttle t = new BotActionPolicy.Throttle();
        for (int i = 0; i < BotActionPolicy.THROTTLE_CAPACITY; i++) {
            assertTrue(t.tryAcquire(1_000L), "burst " + i);
        }
        assertFalse(t.tryAcquire(1_000L));
        assertFalse(t.tryAcquire(1_050L));
        assertTrue(t.tryAcquire(1_150L));
        assertFalse(t.tryAcquire(1_150L));
        assertTrue(t.tryAcquire(10_000L));
    }
}
