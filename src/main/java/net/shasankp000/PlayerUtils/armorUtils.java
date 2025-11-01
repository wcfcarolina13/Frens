package net.shasankp000.PlayerUtils;

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

import java.util.ArrayList;
import java.util.List;


public class armorUtils {
    public static void autoEquipArmor(ServerPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();

        // Prepare a list of equipment updates to notify clients
        List<Pair<EquipmentSlot, ItemStack>> equipmentUpdates = new ArrayList<>();

        // Iterate through all armor slots
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack equippedArmor = bot.getEquippedStack(slot);

            // Find the best armor piece in the inventory for this slot
            ItemStack bestArmor = findBestArmor(inventory, slot);

            // Equip the armor if it's better than what's currently equipped
            if (!bestArmor.isEmpty() && (equippedArmor.isEmpty() || isBetterArmor(bestArmor, equippedArmor, slot))) {
                ItemStack stackToEquip = bestArmor.copy();
                bot.equipStack(slot, stackToEquip);
                inventory.removeOne(bestArmor); // Remove the equipped armor from inventory
                System.out.println("Equipped " + stackToEquip.getName().getString() + " in slot " + slot.getName());

                // Add this update to the list for notifying clients
                equipmentUpdates.add(new Pair<>(slot, stackToEquip)); // Use com.mojang.datafixers.util.Pair
            }
        }

        // Send the equipment update packet to all nearby players
        if (!equipmentUpdates.isEmpty()) {
            bot.getEntityWorld().getPlayers().forEach(player ->
                    player.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(bot.getId(), equipmentUpdates))
            );
        }
    }

    private static boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == slot;
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
    private static ItemStack findBestArmor(PlayerInventory inventory, EquipmentSlot slot) {
        ItemStack bestArmor = ItemStack.EMPTY;
        double bestScore = 0.0;

        for (ItemStack item : inventory.getMainStacks()) {
            if (!item.isEmpty() && isArmorForSlot(item, slot)) {
                double score = getArmorScore(item, slot);
                if (score > bestScore) {
                    bestScore = score;
                    bestArmor = item;
                }
            }
        }
        return bestArmor;
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


}
