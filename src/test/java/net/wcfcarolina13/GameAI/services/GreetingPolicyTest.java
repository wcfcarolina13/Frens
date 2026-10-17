package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreetingPolicyTest {

    @Test
    void freshBotMeetingAPlayerForTheFirstTimeUsesFirstMeeting() {
        assertEquals(GreetingPolicy.Pool.FIRST_MEETING, GreetingPolicy.pool(true, true, false));
    }

    @Test
    void returningBotOrLongAbsenceUsesReunion() {
        assertEquals(GreetingPolicy.Pool.REUNION, GreetingPolicy.pool(false, true, false));
        assertEquals(GreetingPolicy.Pool.REUNION, GreetingPolicy.pool(false, false, true));
        // A fresh bot that already met this player this session can welcome them back later.
        assertEquals(GreetingPolicy.Pool.REUNION, GreetingPolicy.pool(true, false, true));
    }

    @Test
    void noGreetingWhenNeitherFirstSightingNorLongAbsence() {
        assertEquals(GreetingPolicy.Pool.NONE, GreetingPolicy.pool(true, false, false));
        assertEquals(GreetingPolicy.Pool.NONE, GreetingPolicy.pool(false, false, false));
        assertTrue(GreetingPolicy.lines(GreetingPolicy.Pool.NONE, "Roti").isEmpty());
        assertTrue(GreetingPolicy.lines(null, "Roti").isEmpty());
    }

    @Test
    void firstMeetingNeverSaysAgainOrBack() {
        List<String> lines = GreetingPolicy.lines(GreetingPolicy.Pool.FIRST_MEETING, "Roti");
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            String lower = line.toLowerCase();
            assertFalse(lower.contains("again"), line);
            assertFalse(lower.contains("back"), line);
        }
    }

    @Test
    void firstMeetingHasNoEmDash() {
        for (String line : GreetingPolicy.lines(GreetingPolicy.Pool.FIRST_MEETING, "Roti")) {
            assertFalse(line.contains("\u2014"), line);
        }
    }

    @Test
    void stringsStayByteIdenticalForVoiceMapping() {
        // Must match the greeting templates in ChatUtils/DialogueTextMapper.
        assertEquals(List.of(
                "Hey, Roti.",
                "Oh, Roti, there you are.",
                "Good to see you again, Roti.",
                "Welcome back, Roti."
        ), GreetingPolicy.lines(GreetingPolicy.Pool.REUNION, "Roti"));
        assertEquals(List.of(
                "Hey, Roti."
        ), GreetingPolicy.lines(GreetingPolicy.Pool.FIRST_MEETING, "Roti"));
    }

    @Test
    void blankNameFallsBackToThere() {
        assertEquals("Hey, there.", GreetingPolicy.lines(GreetingPolicy.Pool.FIRST_MEETING, " ").get(0));
        assertEquals("Hey, there.", GreetingPolicy.lines(GreetingPolicy.Pool.REUNION, null).get(0));
    }
}
