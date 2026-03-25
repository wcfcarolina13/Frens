package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bot-scoped ephemeral memory for woodcut cleanup leftovers.
 */
public final class WoodcutCleanupMemoryService {

    private static final String FLOATER_PREFIX = "woodcut.cleanupFloaters.";
    private static final int MAX_FLOATERS_PER_BOT_DIMENSION = 1024;

    private WoodcutCleanupMemoryService() {
    }

    public static Set<Long> getOrCreate(Map<String, Object> sharedState, UUID botUuid, ServerWorld world) {
        if (sharedState == null || botUuid == null || world == null) {
            return new HashSet<>();
        }
        String key = floaterKey(botUuid, world);
        Object raw = sharedState.get(key);
        if (raw instanceof Set<?> set) {
            try {
                @SuppressWarnings("unchecked")
                Set<Long> typed = (Set<Long>) set;
                return typed;
            } catch (ClassCastException ignored) {
                sharedState.remove(key);
            }
        }
        Set<Long> created = new HashSet<>();
        sharedState.put(key, created);
        return created;
    }

    public static void remember(Map<String, Object> sharedState, UUID botUuid, ServerWorld world, BlockPos pos) {
        if (pos == null) {
            return;
        }
        Set<Long> set = getOrCreate(sharedState, botUuid, world);
        set.add(pos.asLong());
        trim(set);
    }

    public static void forget(Map<String, Object> sharedState, UUID botUuid, ServerWorld world, BlockPos pos) {
        if (sharedState == null || botUuid == null || world == null || pos == null) {
            return;
        }
        Set<Long> set = getOrCreate(sharedState, botUuid, world);
        set.remove(pos.asLong());
    }

    public static void clearForBot(Map<String, Object> sharedState, UUID botUuid) {
        if (sharedState == null || botUuid == null) {
            return;
        }
        String prefix = FLOATER_PREFIX + botUuid + ".";
        sharedState.keySet().removeIf(key -> key != null && key.startsWith(prefix));
    }

    private static String floaterKey(UUID botUuid, ServerWorld world) {
        return FLOATER_PREFIX + botUuid + "." + world.getRegistryKey().getValue();
    }

    private static void trim(Set<Long> set) {
        int overflow = set.size() - MAX_FLOATERS_PER_BOT_DIMENSION;
        if (overflow <= 0) {
            return;
        }
        var it = set.iterator();
        while (overflow > 0 && it.hasNext()) {
            it.next();
            it.remove();
            overflow--;
        }
    }
}
