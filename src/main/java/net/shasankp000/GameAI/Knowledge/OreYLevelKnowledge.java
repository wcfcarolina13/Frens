package net.shasankp000.GameAI.Knowledge;

import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import java.util.Optional;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class OreYLevelKnowledge {

    private static final Map<Identifier, OreYLevelInfo> ORE_Y_LEVELS;

    static {
        Map<Identifier, OreYLevelInfo> aMap = new HashMap<>();

        // Overworld Ores
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.COAL_ORE), new OreYLevelInfo(-64, 320, 95));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.COPPER_ORE), new OreYLevelInfo(-16, 112, 48));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.IRON_ORE), new OreYLevelInfo(-24, 56, 15));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.GOLD_ORE), new OreYLevelInfo(-64, 32, -16));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.REDSTONE_ORE), new OreYLevelInfo(-64, 16, -32));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.LAPIS_ORE), new OreYLevelInfo(-64, 0, -32));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.DIAMOND_ORE), new OreYLevelInfo(-64, 16, -58));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.EMERALD_ORE), new OreYLevelInfo(-32, 32, 230)); // Mountains

        // Nether Ores
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.NETHER_QUARTZ_ORE), new OreYLevelInfo(10, 117, 64));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.ANCIENT_DEBRIS), new OreYLevelInfo(8, 22, 15));
        aMap.put(Registries.BLOCK.getId(net.minecraft.block.Blocks.NETHER_GOLD_ORE), new OreYLevelInfo(10, 117, 64));

        ORE_Y_LEVELS = Collections.unmodifiableMap(aMap);
    }

    private OreYLevelKnowledge() {
        // Private constructor to prevent instantiation
    }

    public static Optional<OreYLevelInfo> getOreInfo(Identifier oreBlockId) {
        return Optional.ofNullable(ORE_Y_LEVELS.get(oreBlockId));
    }

    public record OreYLevelInfo(int minLevel, int maxLevel, int optimalLevel) {
    }
}
