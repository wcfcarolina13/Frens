package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CookHobbyTaskWiringTest {

    @Test
    void cookHobbyHoldsAnAmbientTaskForItsWholeCycle() throws Exception {
        // Without a task the idle scheduler started another hobby a few seconds into the cook
        // (seen headless 2026-11-07: wander walked the bot off the furnace twice).
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java"));
        int begin = src.indexOf("TaskService.beginAmbientSkill(CookHobbyTaskPolicy.TASK_NAME, botSource, botUuid)");
        int submit = src.indexOf("AMBIENT_EXECUTOR.submit(", begin);
        int cook = src.indexOf("SmeltingService.cookAllFoodSync(bot, botSource, sw)", submit);
        int complete = src.indexOf("TaskService.complete(cookTicket, cookSuccess);", cook);
        int rejected = src.indexOf("TaskService.complete(cookTicket, false);", complete);
        assertTrue(begin > 0, "ticket taken before the submit");
        assertTrue(submit > begin && cook > submit, "cook runs on the worker");
        assertTrue(complete > cook, "ticket released in the worker's finally");
        assertTrue(rejected > complete, "ticket released when the submit is rejected");
    }
}
