package net.shasankp000.GameAI.skills;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small helper that maps block identifiers to the items that should be credited when the block
 * is mined. This lets collection skills count the correct drops even when Silk Touch is not used
 * (e.g. stone producing cobblestone).
 */
public final class BlockDropRegistry {

    private static final Map<Identifier, Set<Item>> DROP_MAP = new ConcurrentHashMap<>();

    static {
        register(Blocks.STONE, Items.COBBLESTONE, Items.STONE);
        register(Blocks.COBBLESTONE, Items.COBBLESTONE);
        register(Blocks.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE);
        register(Blocks.COBBLED_DEEPSLATE, Items.COBBLED_DEEPSLATE);
    }

    private BlockDropRegistry() {
    }

    public static void register(Block block, Item... drops) {
        if (block == null) {
            return;
        }
        Identifier id = Registries.BLOCK.getId(block);
        register(id, drops);
    }

    public static void register(Identifier identifier, Item... drops) {
        if (identifier == null || drops == null || drops.length == 0) {
            return;
        }
        DROP_MAP.compute(identifier, (id, existing) -> {
            Set<Item> values = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing);
            Collections.addAll(values, drops);
            return Collections.unmodifiableSet(values);
        });
    }

    public static Set<Item> dropsFor(Identifier identifier) {
        if (identifier == null) {
            return Collections.emptySet();
        }
        return DROP_MAP.getOrDefault(identifier, Collections.emptySet());
    }
}
