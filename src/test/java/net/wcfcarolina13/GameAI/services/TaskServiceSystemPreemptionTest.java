package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceSystemPreemptionTest {

    @AfterEach
    void tearDown() {
        TaskService.resetAll("test-cleanup");
    }

    @Test
    void commandSkillPreemptsSystemTaskForSameBot() {
        UUID botId = UUID.randomUUID();
        Optional<TaskService.TaskTicket> systemTicket = TaskService.beginSystemTask("surface_recovery", null, botId);
        assertTrue(systemTicket.isPresent());

        Optional<TaskService.TaskTicket> commandTicket = TaskService.beginSkill("woodcut", null, botId);
        assertTrue(commandTicket.isPresent());
        assertEquals("skill:woodcut", TaskService.getActiveTaskName(botId).orElseThrow());
    }
}
