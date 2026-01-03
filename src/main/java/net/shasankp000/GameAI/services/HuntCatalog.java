package net.shasankp000.GameAI.services;

import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical huntable-mob catalog (food mobs + special hostile targets).
 */
public final class HuntCatalog {

    public record HuntTarget(Identifier id,
                             EntityType<?> type,
                             String label,
                             boolean peaceful,
                             boolean foodMob,
                             boolean zombie) {
    }

    private static final List<HuntTarget> TARGETS = new ArrayList<>();
    private static final Map<String, HuntTarget> NAME_MAP = new HashMap<>();
    private static final Set<EntityType<?>> FOOD_TYPES = Set.of(
            EntityType.COW,
            EntityType.MOOSHROOM,
            EntityType.PIG,
            EntityType.CHICKEN,
            EntityType.SHEEP,
            EntityType.RABBIT,
            EntityType.COD,
            EntityType.SALMON,
            EntityType.TROPICAL_FISH,
            EntityType.PUFFERFISH
    );

    static {
        register(EntityType.ZOMBIE, false, false, true, "Zombie", "zombie", "zombies");
        register(EntityType.COW, true, true, false, "Cow", "cow", "cows");
        register(EntityType.MOOSHROOM, true, true, false, "Mooshroom", "mooshroom", "mushroom_cow", "mushroom_cows", "mooshrooms");
        register(EntityType.PIG, true, true, false, "Pig", "pig", "pigs");
        register(EntityType.CHICKEN, true, true, false, "Chicken", "chicken", "chickens");
        register(EntityType.SHEEP, true, true, false, "Sheep", "sheep");
        register(EntityType.RABBIT, true, true, false, "Rabbit", "rabbit", "rabbits");
        register(EntityType.COD, true, true, false, "Cod", "cod");
        register(EntityType.SALMON, true, true, false, "Salmon", "salmon");
        register(EntityType.TROPICAL_FISH, true, true, false, "Tropical Fish", "tropical_fish", "tropicalfish", "tropical");
        register(EntityType.PUFFERFISH, true, true, false, "Pufferfish", "pufferfish", "puffer");
    }

    private HuntCatalog() {
    }

    private static void register(EntityType<?> type,
                                 boolean peaceful,
                                 boolean foodMob,
                                 boolean zombie,
                                 String label,
                                 String... names) {
        Identifier id = EntityType.getId(type);
        HuntTarget target = new HuntTarget(id, type, label, peaceful, foodMob, zombie);
        TARGETS.add(target);
        if (names != null) {
            for (String raw : names) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                NAME_MAP.put(raw.toLowerCase(Locale.ROOT), target);
            }
        }
        NAME_MAP.put(id.toString().toLowerCase(Locale.ROOT), target);
        NAME_MAP.put(id.getPath().toLowerCase(Locale.ROOT), target);
    }

    public static List<HuntTarget> listAll() {
        return Collections.unmodifiableList(TARGETS);
    }

    public static HuntTarget findByName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return NAME_MAP.get(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isFoodMob(EntityType<?> type) {
        return type != null && FOOD_TYPES.contains(type);
    }
}
