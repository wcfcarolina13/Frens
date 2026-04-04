package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class LodestoneCompassService {

    private static final Logger LOGGER = LoggerFactory.getLogger("lodestone-compass");

    private LodestoneCompassService() {}

    public record LodestoneCompassEntry(int slot, String displayName, GlobalPos target) {}

    public static List<LodestoneCompassEntry> findLodestoneCompasses(ServerPlayerEntity bot) {
        List<LodestoneCompassEntry> results = new ArrayList<>();
        if (bot == null) return results;
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;

            // Check direct compass
            if (stack.isOf(Items.COMPASS)) {
                checkAndAddCompass(stack, i, results);
                continue;
            }

            // Check inside bundles
            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                int slot = i;
                for (ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && !bundled.isEmpty() && bundled.isOf(Items.COMPASS)) {
                        checkAndAddCompass(bundled, slot, results);
                    }
                }
            }
        }
        return results;
    }

    private static void checkAndAddCompass(ItemStack stack, int slot, List<LodestoneCompassEntry> results) {
        LodestoneTrackerComponent tracker = stack.get(DataComponentTypes.LODESTONE_TRACKER);
        if (tracker == null) return;
        tracker.target().ifPresent(globalPos -> {
            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
            String displayName = customName != null ? customName.getString() : "Lodestone Compass";
            results.add(new LodestoneCompassEntry(slot, displayName, globalPos));
        });
    }

    public static boolean validateLodestone(MinecraftServer server, GlobalPos target) {
        if (server == null || target == null) return false;
        RegistryKey<World> dimKey = target.dimension();
        ServerWorld world = server.getWorld(dimKey);
        if (world == null) return false;
        BlockPos pos = target.pos();
        WorldChunk chunk = world.getChunkManager().getWorldChunk(
                pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            LOGGER.debug("Cannot validate lodestone at {} in {} — chunk not loaded",
                    pos.toShortString(), dimKey.getValue());
            return false;
        }
        return world.getBlockState(pos).isOf(Blocks.LODESTONE);
    }

    public static boolean hasLodestoneCompass(ServerPlayerEntity bot) {
        return !findLodestoneCompasses(bot).isEmpty();
    }

    /**
     * Count compasses that have a LODESTONE_TRACKER component but whose target is empty
     * (lodestone was destroyed). These compasses still look like lodestone compasses
     * (glint, name) but point nowhere and need re-anchoring.
     */
    public static int countOrphanedCompasses(ServerPlayerEntity bot) {
        if (bot == null) return 0;
        int count = 0;
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            count += countOrphanedInStack(stack);
            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && !bundled.isEmpty()) {
                        count += countOrphanedInStack(bundled);
                    }
                }
            }
        }
        return count;
    }

    private static int countOrphanedInStack(ItemStack stack) {
        if (!stack.isOf(Items.COMPASS)) return 0;
        LodestoneTrackerComponent tracker = stack.get(DataComponentTypes.LODESTONE_TRACKER);
        return (tracker != null && tracker.target().isEmpty()) ? 1 : 0;
    }

    /**
     * Lenient check: returns true if the bot has ANY compass with a LODESTONE_TRACKER
     * component, even if the target is empty (lodestone destroyed). Used by the underground
     * fast-travel gate to recognize lodestone compasses as navigation tools regardless of
     * whether the specific lodestone still exists.
     */
    public static boolean hasAnyLodestoneCompass(ServerPlayerEntity bot) {
        if (bot == null) return false;
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            if (stack.isOf(Items.COMPASS) && stack.get(DataComponentTypes.LODESTONE_TRACKER) != null) return true;
            // Check inside bundles
            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && !bundled.isEmpty() && bundled.isOf(Items.COMPASS)
                            && bundled.get(DataComponentTypes.LODESTONE_TRACKER) != null) return true;
                }
            }
        }
        return false;
    }

    public static Optional<LodestoneCompassEntry> selectCompassByName(
            ServerPlayerEntity bot, String name) {
        if (bot == null || name == null || name.isBlank()) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);
        return findLodestoneCompasses(bot).stream()
                .filter(e -> e.displayName().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst();
    }

    public static Optional<LodestoneCompassEntry> selectAutonomousCompass(
            ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return Optional.empty();
        List<LodestoneCompassEntry> compasses = findLodestoneCompasses(bot);
        if (compasses.isEmpty()) return Optional.empty();

        // 1. Try designated home compass
        String homeName = getHomeCompassName(bot);
        if (homeName != null && !homeName.isBlank()) {
            String lower = homeName.toLowerCase(Locale.ROOT);
            Optional<LodestoneCompassEntry> home = compasses.stream()
                    .filter(e -> e.displayName().toLowerCase(Locale.ROOT).equals(lower))
                    .findFirst();
            if (home.isPresent() && validateLodestone(server, home.get().target())) {
                return home;
            }
        }

        // 2. Same-dimension compasses sorted by distance
        RegistryKey<World> botDim = bot.getEntityWorld().getRegistryKey();
        BlockPos botPos = bot.getBlockPos();
        Optional<LodestoneCompassEntry> sameDim = compasses.stream()
                .filter(e -> e.target().dimension().equals(botDim))
                .sorted(Comparator.comparingDouble(e ->
                        botPos.getSquaredDistance(e.target().pos())))
                .filter(e -> validateLodestone(server, e.target()))
                .findFirst();
        if (sameDim.isPresent()) return sameDim;

        // 3. Cross-dimension: inventory order, first-validates-wins
        return compasses.stream()
                .filter(e -> !e.target().dimension().equals(botDim))
                .filter(e -> validateLodestone(server, e.target()))
                .findFirst();
    }

    public static String getHomeCompassName(ServerPlayerEntity bot) {
        return BotHomeService.getHomeCompassName(bot);
    }

    public static void setHomeCompassName(ServerPlayerEntity bot, String compassName) {
        BotHomeService.setHomeCompassName(bot, compassName);
    }
}
