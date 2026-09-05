package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the parts of LeafClearService that need no world. */
class LeafClearServiceTest {

    @Test
    void decayingLeafRequiresDistanceSevenAndNonPersistent() {
        assertTrue(LeafClearService.isDecayingLeaf(7, false));
        assertFalse(LeafClearService.isDecayingLeaf(6, false));
        assertFalse(LeafClearService.isDecayingLeaf(7, true), "player-placed leaves never decay");
        assertFalse(LeafClearService.isDecayingLeaf(1, true));
    }

    @Test
    void lerpSamplesUseAtLeastFourStepsAndExcludeEndpoints() {
        List<BlockPos> samples = LeafClearService.lerpSampleCandidates(BlockPos.ORIGIN, new BlockPos(1, 0, 0));
        // distance 1 -> ceil(4) = 4 steps, i in [1,4) -> 3 samples
        assertEquals(3, samples.size());
        assertFalse(samples.contains(new BlockPos(2, 0, 0)));
    }

    @Test
    void lerpSampleCountScalesWithDistance() {
        List<BlockPos> near = LeafClearService.lerpSampleCandidates(BlockPos.ORIGIN, new BlockPos(2, 0, 0));
        List<BlockPos> far = LeafClearService.lerpSampleCandidates(BlockPos.ORIGIN, new BlockPos(8, 0, 0));
        assertEquals(7, near.size());
        assertEquals(31, far.size());
        assertTrue(far.size() > near.size());
    }

    @Test
    void lerpSamplesAreOrderedFromEyeTowardTarget() {
        List<BlockPos> samples = LeafClearService.lerpSampleCandidates(BlockPos.ORIGIN, new BlockPos(8, 0, 0));
        assertTrue(samples.get(0).getX() <= samples.get(samples.size() - 1).getX());
        assertEquals(0, samples.get(0).getX());
        assertEquals(8, samples.get(samples.size() - 1).getX());
    }

    @Test
    void nullInputsYieldNoSamples() {
        assertTrue(LeafClearService.lerpSampleCandidates((BlockPos) null, BlockPos.ORIGIN).isEmpty());
        assertTrue(LeafClearService.lerpSampleCandidates(BlockPos.ORIGIN, (BlockPos) null).isEmpty());
    }
}
