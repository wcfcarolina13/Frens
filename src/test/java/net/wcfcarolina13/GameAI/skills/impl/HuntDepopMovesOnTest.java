package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hunt over several kinds used to end as soon as one kind ran low ("Population getting thin"),
 * even with another ticked kind plentiful. It now looks for another kind before heading back.
 */
class HuntDepopMovesOnTest {

    @Test
    void thinKindLooksForAnotherBeforeEnding() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/skills/impl/HuntSkill.java"));
        int thin = src.indexOf("\"Population getting thin. Heading back. (\"");
        int block = src.lastIndexOf("if (remaining < MIN_PEACEFUL_COUNT)", thin);
        String body = src.substring(block, thin);
        assertTrue(body.contains("explicitTarget == null"), "a named hunt still ends");
        assertTrue(body.contains("findCandidate("), "a multi-kind hunt checks for another kind first");
        assertTrue(src.substring(thin, thin + 400).contains("Moving on to other animals."));
    }
}
