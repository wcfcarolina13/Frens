package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.util.math.BlockPos;

/**
 * Tracks a temporary roof access pillar that couldn't be fully torn down.
 *
 * @param base base position (Y at bottom block of pillar)
 * @param topY Y of the air cell just above the pillar top (standable perch)
 */
record RoofPillar(BlockPos base, int topY) {
}
