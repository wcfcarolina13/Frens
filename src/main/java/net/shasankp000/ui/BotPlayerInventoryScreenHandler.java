package net.shasankp000.ui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.AIPlayer;

/**
 * Screen handler that mirrors the vanilla player inventory for both bot and viewer.
 */
public class BotPlayerInventoryScreenHandler extends ScreenHandler {
    private static final int BOT_SLOT_COUNT = 41;
    private static final int PLAYER_SLOT_COUNT = 41;
    private static final int SECTION_WIDTH = 176;
    private static final int BLOCK_GAP = 12;
    private static final int ARMOR_X = 8;
    private static final int ARMOR_Y = 8;
    private static final int OFFHAND_X = 77;
    private static final int OFFHAND_Y = 62;
    private static final int GRID_X = 8;
    private static final int MAIN_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final Inventory botInventory;
    private final PlayerInventory playerInventory;
    private final ServerPlayerEntity botRef;
    private final PropertyDelegate botStats;

    public static BotPlayerInventoryScreenHandler clientFactory(int syncId, PlayerInventory playerInventory) {
        return new BotPlayerInventoryScreenHandler(syncId, playerInventory, new SimpleInventory(BOT_SLOT_COUNT), null);
    }

    public BotPlayerInventoryScreenHandler(int syncId,
                                           PlayerInventory playerInventory,
                                           Inventory botInventory,
                                           ServerPlayerEntity botRef) {
        super(AIPlayer.BOT_PLAYER_INV_HANDLER, syncId);
        this.playerInventory = playerInventory;
        this.botInventory = botInventory;
        this.botRef = botRef;
        this.botStats = new ArrayPropertyDelegate(4);
        this.addProperties(this.botStats);
        refreshStats();

        botInventory.onOpen(playerInventory.player);

        int offsetBot = 0;
        int offsetPlayer = SECTION_WIDTH + BLOCK_GAP;

        addArmorAndOffhand(botInventory, offsetBot);
        addMainGrid(botInventory, 9, offsetBot);
        addHotbar(botInventory, 0, offsetBot);

        addArmorAndOffhand(playerInventory, offsetPlayer);
        addMainGrid(playerInventory, 9, offsetPlayer);
        addHotbar(playerInventory, 0, offsetPlayer);
    }

    private void addArmorAndOffhand(Inventory inventory, int xOffset) {
        int armorIndexStart = 39;
        for (int i = 0; i < 4; i++) {
            int slotIndex = armorIndexStart - i;
            addSlot(new Slot(inventory, slotIndex, ARMOR_X + xOffset, ARMOR_Y + i * 18));
        }
        addSlot(new Slot(inventory, 40, OFFHAND_X + xOffset, OFFHAND_Y));
    }

    private void addMainGrid(Inventory inventory, int startIndex, int xOffset) {
        int slot = startIndex;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, slot++, GRID_X + xOffset + col * 18, MAIN_Y + row * 18));
            }
        }
    }

    private void addHotbar(Inventory inventory, int startIndex, int xOffset) {
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, startIndex + col, GRID_X + xOffset + col * 18, HOTBAR_Y));
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (botRef == null) return true;
        return player.getEntityWorld() == botRef.getEntityWorld() && player.squaredDistanceTo(botRef) <= 64.0;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack original = slot.getStack();
            newStack = original.copy();

            if (index < BOT_SLOT_COUNT) {
                if (!this.insertItem(original, BOT_SLOT_COUNT, BOT_SLOT_COUNT + PLAYER_SLOT_COUNT, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.insertItem(original, 0, BOT_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }

            if (original.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            } else {
                slot.markDirty();
            }

            if (original.getCount() == newStack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTakeItem(player, original);
        }
        return newStack;
    }

    @Override
    public void sendContentUpdates() {
        refreshStats();
        super.sendContentUpdates();
    }

    private void refreshStats() {
        if (botRef == null) return;
        botStats.set(0, (int) Math.round(botRef.getHealth() * 10));
        botStats.set(1, (int) Math.round(botRef.getMaxHealth() * 10));
        botStats.set(2, botRef.getHungerManager().getFoodLevel());
        botStats.set(3, botRef.experienceLevel);
    }

    public float getBotHealth() {
        return botStats.get(0) / 10.0f;
    }

    public float getBotMaxHealth() {
        return Math.max(1.0f, botStats.get(1) / 10.0f);
    }

    public int getBotHunger() {
        return botStats.get(2);
    }

    public int getBotLevel() {
        return botStats.get(3);
    }

    public int getSectionWidth() {
        return SECTION_WIDTH;
    }

    public int getBlockGap() {
        return BLOCK_GAP;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        botInventory.onClose(player);
    }
}
