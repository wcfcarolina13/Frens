package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #128: the moat skill placed none of its floor, lining or overhang; the wall build's finish
 * report counted moat cells and so always said "Partial"; and Resume skipped every side already
 * more than 40% built because the overlap check counted the wall's own blocks.
 */
class FortifyMoatAndReportTest {

    private static final Path SKILL = Path.of("src/main/java/net/wcfcarolina13/GameAI/skills/impl/FortifyVillageSkill.java");

    @Test
    void ownBlocksNeverCountAsOverlap() {
        assertFalse(FortifyOverlapPolicy.countsAsOverlap(false, true), "the wall's own block is not overlap");
        assertFalse(FortifyOverlapPolicy.countsAsOverlap(true, false), "an open cell is not overlap");
        assertTrue(FortifyOverlapPolicy.countsAsOverlap(false, false), "a foreign solid block is overlap");
    }

    @Test
    void skipsOnlyWhenMoreThanFortyPercentIsForeign() {
        assertFalse(FortifyOverlapPolicy.shouldSkipEdge(100, 40));
        assertTrue(FortifyOverlapPolicy.shouldSkipEdge(100, 41));
        assertFalse(FortifyOverlapPolicy.shouldSkipEdge(0, 0));
    }

    @Test
    void moatPlacementDoesNotDropMoatBlocks() throws IOException {
        String src = Files.readString(SKILL);
        int at = src.indexOf("private int buildBlockList(");
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertFalse(body.contains("isActiveFortifyBlock("),
                "buildBlockList's only caller is the moat skill; filtering out moat types places nothing");
    }

    @Test
    void finishReportCountsOnlyTheBlocksTheWallBuildPlaces() throws IOException {
        String src = Files.readString(SKILL);
        int at = src.indexOf("// Final report");
        String report = src.substring(at, src.indexOf("EDGE_COMPLETION_THRESHOLD", at));
        assertTrue(report.contains("countActiveBlocks("));
        assertFalse(report.contains("int totalPlanned = layout.allBlocks().size();"));
    }
}
