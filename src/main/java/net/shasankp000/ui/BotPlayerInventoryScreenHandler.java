package net.shasankp000.ui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.AIPlayer;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotCommandStateService;
import net.shasankp000.GameAI.services.BotInventoryStorageService;

/**
 * Screen handler that mirrors the vanilla player inventory for both bot and viewer.
 */
public class BotPlayerInventoryScreenHandler extends ScreenHandler {
    private static final int BOT_SLOT_COUNT = 41;
    private static final int PLAYER_SLOT_COUNT = 41;
    private static final int ARMOR_AND_OFFHAND_SLOTS = 5;
    private static final int MAIN_GRID_SLOTS = 27;
    private static final int HOTBAR_SLOTS = 9;
    private static final int SECTION_TOTAL_SLOTS = ARMOR_AND_OFFHAND_SLOTS + MAIN_GRID_SLOTS + HOTBAR_SLOTS;
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
        this.botStats = new ArrayPropertyDelegate(10);
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
        if (player instanceof ServerPlayerEntity serverPlayer && serverPlayer.hasPermissionLevel(2)) {
            return true;
        }
        return player.getEntityWorld() == botRef.getEntityWorld() && player.squaredDistanceTo(botRef) <= 64.0;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack original = slot.getStack();
            newStack = original.copy();

            boolean moved;
            if (index < BOT_SLOT_COUNT) {
                moved = moveIntoSection(original, BOT_SLOT_COUNT, false);
            } else {
                moved = moveIntoSection(original, 0, true);
            }
            if (!moved) {
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

    /**
     * Shift-click routing that avoids dumping arbitrary items into armor/offhand slots.
     *
     * <p>Policy:
     * - Armor items -> their matching armor slot (if empty), otherwise main/hotbar
     * - Shields -> offhand slot (if empty), otherwise main/hotbar
     * - Everything else -> hotbar first, then main grid
     *
     * <p>This only affects shift-click insertion order; manual drag/drop can still place items anywhere.
     */
    private boolean moveIntoSection(ItemStack stack, int sectionStart, boolean fromPlayerToBot) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        int armorStart = sectionStart;
        int offhandIndex = sectionStart + 4;
        int mainStart = sectionStart + ARMOR_AND_OFFHAND_SLOTS;
        int hotbarStart = mainStart + MAIN_GRID_SLOTS;
        int sectionEnd = sectionStart + SECTION_TOTAL_SLOTS;

        // 1) Dedicated slots for matching equipment (only when empty).
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        EquipmentSlot eqSlot = equippable != null ? equippable.slot() : null;
        if (eqSlot == EquipmentSlot.HEAD || eqSlot == EquipmentSlot.CHEST || eqSlot == EquipmentSlot.LEGS || eqSlot == EquipmentSlot.FEET) {
            int target = armorSlotIndexFor(eqSlot, sectionStart);
            if (target >= sectionStart && target < sectionStart + 4 && !this.slots.get(target).hasStack()) {
                if (this.insertItem(stack, target, target + 1, false)) {
                    return true;
                }
            }
        } else if (stack.isOf(Items.SHIELD)) {
            if (!this.slots.get(offhandIndex).hasStack()) {
                if (this.insertItem(stack, offhandIndex, offhandIndex + 1, false)) {
                    return true;
                }
            }
        }

        // 2) Prefer hotbar first (weapons/tools/food go here), then main grid.
        if (this.insertItem(stack, hotbarStart, sectionEnd, false)) {
            return true;
        }
        if (this.insertItem(stack, mainStart, hotbarStart, false)) {
            return true;
        }

        // Never spill arbitrary items into armor/offhand via shift-click.
        return false;
    }

    private int armorSlotIndexFor(EquipmentSlot slot, int sectionStart) {
        // Slot order in this handler is FEET, LEGS, CHEST, HEAD.
        if (slot == EquipmentSlot.FEET) return sectionStart;
        if (slot == EquipmentSlot.LEGS) return sectionStart + 1;
        if (slot == EquipmentSlot.CHEST) return sectionStart + 2;
        if (slot == EquipmentSlot.HEAD) return sectionStart + 3;
        return -1;
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
        botStats.set(4, (int) Math.round(botRef.experienceProgress * 1000.0F));
        botStats.set(5, botRef.totalExperience);
        BotCommandStateService.State state = BotCommandStateService.stateFor(botRef);
        BotEventHandler.Mode mode = state != null ? state.mode : BotEventHandler.Mode.IDLE;
        botStats.set(6, mode == BotEventHandler.Mode.FOLLOW ? 1 : 0);
        botStats.set(7, mode == BotEventHandler.Mode.GUARD ? 1 : 0);
        botStats.set(8, mode == BotEventHandler.Mode.PATROL ? 1 : 0);
        double followDistance = state != null ? state.followStandoffRange : 0.0D;
        botStats.set(9, (int) Math.round(Math.max(0.0D, followDistance) * 10.0D));
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

    public float getBotXpProgress() {
        return Math.max(0.0F, Math.min(1.0F, botStats.get(4) / 1000.0F));
    }

    public int getBotTotalExperience() {
        return botStats.get(5);
    }

    public boolean isBotFollowing() {
        return botStats.get(6) != 0;
    }

    public boolean isBotGuarding() {
        return botStats.get(7) != 0;
    }

    public boolean isBotPatrolling() {
        return botStats.get(8) != 0;
    }

    public double getBotFollowDistance() {
        return botStats.get(9) / 10.0D;
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
        
        // Save bot inventory immediately when screen is closed
        // This prevents inventory desync when player quickly leaves and rejoins
        if (botRef != null && !botRef.isRemoved()) {
            BotInventoryStorageService.save(botRef);
        }
    }
}
