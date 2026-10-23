package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.292: the idle resource woodcut arms its in-flight guard before submitting the job. */
class IdleResourceWoodcutInFlightWiringTest {
    private static final Path SOURCE =
            Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java");

    @Test
    void decisionCooldownIsArmedBeforeTheWoodcutIsSubmitted() throws Exception {
        String src = Files.readString(SOURCE);
        int start = src.indexOf("private static boolean maybeHandleIdleWoodcutForResources(");
        assertTrue(start > 0);
        String body = src.substring(start, src.indexOf("\n    }\n", start));
        int submit = body.indexOf("startAmbientSkill(server, bot, \"woodcut\");");
        int decision = body.indexOf("NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + WOODEN_FALLBACK_COOLDOWN_TICKS);");
        int fallback = body.indexOf("NEXT_WOODEN_FALLBACK_TICK.put(bot.getUuid(), nowTick + WOODEN_FALLBACK_COOLDOWN_TICKS);");
        assertTrue(submit > 0, "resource woodcut still submits the skill");
        assertTrue(decision > 0 && decision < submit, "decision cooldown must be set before the submit");
        assertTrue(fallback > 0 && fallback < submit, "fallback cooldown must be set before the submit");
        // The gate this guard feeds is still checked first.
        assertTrue(body.indexOf("nowTick < NEXT_DECISION_TICK.getOrDefault(bot.getUuid(), 0L)") < decision);
    }
}
