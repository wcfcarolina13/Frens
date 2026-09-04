package net.wcfcarolina13.PlayerUtils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import com.mojang.datafixers.util.Pair; // Import the correct Pair class
import net.minecraft.registry.entry.RegistryEntry;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.ElytraFlightService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


public class armorUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("armor-utils");

    public static void autoEquipArmor(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        PlayerInventory inventory = bot.getInventory();

        // Prepare a list of equipment updates to notify clients
        List<Pair<EquipmentSlot, ItemStack>> equipmentUpdates = new ArrayList<>();
        correctInvalidEquippedArmor(bot, inventory, equipmentUpdates);

        // Iterate through all armor slots
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (slot == EquipmentSlot.CHEST && ElytraFlightService.isInFlight(bot.getUuid())) {
                continue;
            }
            ItemStack equippedArmor = bot.getEquippedStack(slot);

            // Find the best armor piece in the inventory for this slot
            int bestArmorSlot = findBestArmorSlot(bot, inventory, slot);
            // Bundle-aware: a strictly better piece may be sitting inside a bundle, invisible to the
            // direct-slot scan. Pull it out, then redo the scan so the normal equip logic runs on a
            // real direct slot (bundled stacks are read-only views and must never be equipped).
            if (reachBetterBundledArmor(bot, slot, bestArmorSlot >= 0 ? getArmorScore(inventory.getStack(bestArmorSlot), slot) : -1.0)) {
                bestArmorSlot = findBestArmorSlot(bot, inventory, slot);
            }
            ItemStack bestArmor = bestArmorSlot >= 0 ? inventory.getStack(bestArmorSlot) : ItemStack.EMPTY;

            // Equip the armor if it's better than what's currently equipped
            if (!bestArmor.isEmpty() && (equippedArmor.isEmpty() || isBetterArmor(bestArmor, equippedArmor, slot))) {
                ItemStack stackToEquip = bestArmor.copy();
                ItemStack displacedArmor = equippedArmor.copy();
                boolean replacedPreservedBelow =
                        !equippedArmor.isEmpty()
                        && DurabilityPolicyService.shouldAvoid(bot, equippedArmor);
                bot.equipStack(slot, stackToEquip);
                if (replacedPreservedBelow) {
                    CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
                }
                if (displacedArmor.isEmpty()) {
                    inventory.setStack(bestArmorSlot, ItemStack.EMPTY);
                } else {
                    inventory.setStack(bestArmorSlot, displacedArmor);
                    LOGGER.info("Armor auto-equip: moved displaced {} into inventory slot {} while equipping {} to {} for {}",
                            displacedArmor.getName().getString(),
                            bestArmorSlot,
                            stackToEquip.getName().getString(),
                            slot.getName(),
                            bot.getName().getString());
                }
                inventory.markDirty();
                LOGGER.info("Armor auto-equip: equipped {} in slot {} for {}",
                        stackToEquip.getName().getString(),
                        slot.getName(),
                        bot.getName().getString());

                // Add this update to the list for notifying clients
                equipmentUpdates.add(new Pair<>(slot, stackToEquip)); // Use com.mojang.datafixers.util.Pair
            } else if (bestArmor.isEmpty()
                    && !equippedArmor.isEmpty()
                    && DurabilityPolicyService.shouldAvoid(bot, equippedArmor)) {
                // If the filter is the reason nothing was equipped AND the currently equipped armor
                // is preserved-below-threshold, request a fallback refresh.
                DurabilityFallbackService.GearCategory cat = switch (slot) {
                    case HEAD -> DurabilityFallbackService.GearCategory.HELMET;
                    case CHEST -> DurabilityFallbackService.GearCategory.CHESTPLATE;
                    case LEGS -> DurabilityFallbackService.GearCategory.LEGGINGS;
                    case FEET -> DurabilityFallbackService.GearCategory.BOOTS;
                    default -> null;
                };
                if (cat != null) {
                    DurabilityFallbackService.requestRefresh(bot, cat);
                }
            }
        }

        // Send the equipment update packet to all nearby players
        if (!equipmentUpdates.isEmpty()) {
            bot.getEntityWorld().getPlayers().forEach(player ->
                    player.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(bot.getId(), equipmentUpdates))
            );
        }
    }

    /**
     * Extracts a bundled armor piece for {@code slot} when it scores strictly better than the best
     * direct-slot candidate. Runs wherever {@code autoEquipArmor} runs (server tick and bot-command
     * paths); {@code BundleService.reachFirst} performs the server-thread hop when needed.
     *
     * @param bestDirectScore score of the best direct candidate, or a negative value when there is none
     * @return true when a piece was moved out of a bundle into a direct slot
     */
    private static boolean reachBetterBundledArmor(ServerPlayerEntity bot, EquipmentSlot slot, double bestDirectScore) {
        if (bot == null) {
            return false;
        }
        ItemStack bestBundled = ItemStack.EMPTY;
        double bestBundledScore = -1.0;
        for (var ref : InventoryIterator.stream(bot).toList()) {
            if (ref.isDirect()) {
                continue;
            }
            ItemStack stack = ref.stack();
            if (stack.isEmpty() || !isArmorForSlot(stack, slot)) {
                continue;
            }
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) {
                continue;
            }
            double score = getArmorScore(stack, slot);
            if (score > bestBundledScore) {
                bestBundledScore = score;
                bestBundled = stack;
            }
        }
        if (bestBundled.isEmpty()) {
            return false;
        }
        // Also require the bundled piece to beat what is already equipped, so we do not churn.
        ItemStack equipped = bot.getEquippedStack(slot);
        double equippedScore = isValidArmorForSlot(equipped, slot) ? getArmorScore(equipped, slot) : -1.0;
        double directBaseline = Math.max(bestDirectScore, equippedScore);
        if (!BundleReachPolicy.shouldReachForBetter(
                (int) Math.round(directBaseline * 100.0), (int) Math.round(bestBundledScore * 100.0), false)) {
            return false;
        }
        final ItemStack wanted = bestBundled;
        boolean reached = net.wcfcarolina13.GameAI.services.BundleService.reachFirst(
                bot, stack -> ItemStack.areItemsAndComponentsEqual(stack, wanted));
        if (reached) {
            LOGGER.info("Armor auto-equip: reached {} out of a bundle for {} ({})",
                    wanted.getItem(), slot.getName(), bot.getName().getString());
        }
        return reached;
    }

    private static boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == slot;
    }

    public static boolean isValidArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        return stack != null && !stack.isEmpty() && isArmorForSlot(stack, slot);
    }

    private static double getArmorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        final double[] score = {0.0};
        stack.applyAttributeModifiers(slot, (RegistryEntry<EntityAttribute> attributeEntry, EntityAttributeModifier modifier) -> {
            if (attributeEntry.equals(EntityAttributes.ARMOR)) {
                score[0] += modifier.value();
            } else if (attributeEntry.equals(EntityAttributes.ARMOR_TOUGHNESS)) {
                score[0] += modifier.value() * 0.1;
            }
        });
        return score[0];
    }

    // Helper method to find the best armor for a specific slot
    private static int findBestArmorSlot(ServerPlayerEntity bot, PlayerInventory inventory, EquipmentSlot slot) {
        int bestArmorSlot = -1;
        double bestScore = 0.0;

        for (int slotIndex = 0; slotIndex < PlayerInventory.MAIN_SIZE; slotIndex++) {
            ItemStack item = inventory.getStack(slotIndex);
            if (!item.isEmpty() && isArmorForSlot(item, slot)) {
                if (DurabilityPolicyService.shouldAvoid(bot, item)) {
                    continue; // filtered: preserved material or enchant below threshold
                }
                double score = getArmorScore(item, slot);
                if (score > bestScore) {
                    bestScore = score;
                    bestArmorSlot = slotIndex;
                }
            }
        }
        return bestArmorSlot;
    }

    // Helper method to compare two armor pieces
    private static boolean isBetterArmor(ItemStack newArmor, ItemStack currentArmor, EquipmentSlot slot) {
        if (newArmor.isEmpty() || !isArmorForSlot(newArmor, slot)) {
            return false;
        }

        if (currentArmor.isEmpty() || !isArmorForSlot(currentArmor, slot)) {
            return true;
        }

        double newScore = getArmorScore(newArmor, slot);
        double currentScore = getArmorScore(currentArmor, slot);
        return newScore > currentScore;
    }

    public static void autoDeEquipArmor(ServerPlayerEntity bot) {
        // still a work-in-progress.

        PlayerInventory inventory = bot.getInventory();

        // Prepare a list of equipment updates to notify clients
        List<Pair<EquipmentSlot, ItemStack>> equipmentUpdates = new ArrayList<>();



        // Iterate through all armor slots
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack equippedArmor = bot.getEquippedStack(slot);

            System.out.println(equippedArmor.getName().getString());

            // If the bot has armor equipped in this slot
            if (!equippedArmor.isEmpty()) {
                // Add the armor back to the inventory
                if (inventory.insertStack(equippedArmor)) {
                    // Clear the equipped armor slot
                    bot.equipStack(slot, ItemStack.EMPTY);
                    inventory.markDirty();

                    // Add this update to the list for notifying clients
                    equipmentUpdates.add(new Pair<>(slot, ItemStack.EMPTY));

                    System.out.println("De-equipped " + equippedArmor.getName().getString() + " from slot " + slot.getName());
                } else {
                    System.out.println("Inventory full! Could not de-equip " + equippedArmor.getName().getString() + " from slot " + slot.getName());
                }
            }
        }

        // Send the equipment update packet to all nearby players
        if (!equipmentUpdates.isEmpty()) {
            bot.getEntityWorld().getPlayers().forEach(player ->
                    player.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(bot.getId(), equipmentUpdates))
            );
        }
    }

    private static void correctInvalidEquippedArmor(ServerPlayerEntity bot,
                                                    PlayerInventory inventory,
                                                    List<Pair<EquipmentSlot, ItemStack>> equipmentUpdates) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (slot == EquipmentSlot.CHEST && ElytraFlightService.isInFlight(bot.getUuid())) {
                continue;
            }
            ItemStack equipped = bot.getEquippedStack(slot);
            if (equipped.isEmpty()) {
                continue;
            }
            EquipmentSlot actualSlot = armorSlotFor(equipped);
            if (actualSlot == null || actualSlot == slot) {
                continue;
            }

            ItemStack targetStack = bot.getEquippedStack(actualSlot);
            EquipmentSlot targetActualSlot = armorSlotFor(targetStack);
            if (!targetStack.isEmpty() && targetActualSlot == slot) {
                bot.equipStack(slot, targetStack.copy());
                bot.equipStack(actualSlot, equipped.copy());
                equipmentUpdates.add(new Pair<>(slot, targetStack.copy()));
                equipmentUpdates.add(new Pair<>(actualSlot, equipped.copy()));
                LOGGER.info("Armor auto-equip: swapped {} from {} with {} from {} for {}",
                        equipped.getName().getString(),
                        slot.getName(),
                        targetStack.getName().getString(),
                        actualSlot.getName(),
                        bot.getName().getString());
                continue;
            }

            if (targetStack.isEmpty()) {
                bot.equipStack(actualSlot, equipped.copy());
                bot.equipStack(slot, ItemStack.EMPTY);
                equipmentUpdates.add(new Pair<>(actualSlot, equipped.copy()));
                equipmentUpdates.add(new Pair<>(slot, ItemStack.EMPTY));
                LOGGER.info("Armor auto-equip: moved {} from {} to {} for {}",
                        equipped.getName().getString(),
                        slot.getName(),
                        actualSlot.getName(),
                        bot.getName().getString());
                continue;
            }

            if (insertIntoMainInventory(inventory, equipped.copy())) {
                bot.equipStack(slot, ItemStack.EMPTY);
                equipmentUpdates.add(new Pair<>(slot, ItemStack.EMPTY));
                LOGGER.info("Armor auto-equip: cleared invalid {} from {} into inventory for {}",
                        equipped.getName().getString(),
                        slot.getName(),
                        bot.getName().getString());
                continue;
            }

            LOGGER.warn("Armor auto-equip: could not clear invalid {} from {} for {} because inventory had no room",
                    equipped.getName().getString(),
                    slot.getName(),
                    bot.getName().getString());
        }
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        EquippableComponent equippable = stack != null ? stack.get(DataComponentTypes.EQUIPPABLE) : null;
        if (equippable == null) {
            return null;
        }
        EquipmentSlot slot = equippable.slot();
        if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
            return slot;
        }
        return null;
    }

    private static boolean insertIntoMainInventory(PlayerInventory inventory, ItemStack stack) {
        if (inventory == null || stack == null || stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack);
                inventory.markDirty();
                return true;
            }
        }
        return false;
    }


}
