package net.shasankp000.GameAI.services;

import com.mojang.serialization.DataResult;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.regex.Pattern;

public final class BotInventoryStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-inventory-storage");

    private static final String KEY_ALIAS = "Alias";
    private static final String KEY_UUID = "Uuid";
    private static final String KEY_SELECTED_SLOT = "SelectedSlot";
    private static final String KEY_INVENTORY = "Inventory";
    private static final String KEY_SLOT = "Slot";
    private static final String KEY_STACK = "Stack";

    private static final Pattern SAFE_NAME = Pattern.compile("[^a-z0-9-_]+");

    private BotInventoryStorageService() {}

    public static boolean save(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }

        Path path = resolveInventoryPath(server, bot);
        if (path == null) {
            return false;
        }

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.warn("Unable to create inventory directory '{}': {}", path.getParent(), e.getMessage());
            return false;
        }

        DynamicRegistryManager registries = server.getRegistryManager();
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);

        NbtList items = new NbtList();
        PlayerInventory inventory = bot.getInventory();

        String botName = bot.getName().getString();

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            final int slotIndex = slot;
            DataResult<NbtElement> encoded = ItemStack.CODEC.encodeStart(ops, stack);
            encoded.resultOrPartial(error -> LOGGER.warn("Failed to encode item in slot {} for '{}': {}", slotIndex, botName, error))
                    .ifPresent(element -> {
                        NbtCompound entry = new NbtCompound();
                        entry.putInt(KEY_SLOT, slotIndex);
                        entry.put(KEY_STACK, element);
                        items.add(entry);
                    });
        }

        NbtCompound root = new NbtCompound();
        root.putString(KEY_ALIAS, bot.getGameProfile().name());
        root.putString(KEY_UUID, bot.getUuidAsString());
        root.putInt(KEY_SELECTED_SLOT, inventory.getSelectedSlot());
        root.put(KEY_INVENTORY, items);

        try {
            net.minecraft.nbt.NbtIo.writeCompressed(root, path);
            LOGGER.info("Saved inventory for fakeplayer '{}' to {}", bot.getName().getString(), path.getFileName());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save inventory for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            return false;
        }
    }

    public static boolean load(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }

        Path path = resolveInventoryPath(server, bot);
        if (path == null || !Files.exists(path)) {
            return false;
        }

        NbtCompound root;
        try {
            root = net.minecraft.nbt.NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        } catch (IOException e) {
            LOGGER.error("Failed to read inventory for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            return false;
        }

        DynamicRegistryManager registries = server.getRegistryManager();
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        PlayerInventory inventory = bot.getInventory();

        // Clear existing inventory.
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }

        root.getInt(KEY_SELECTED_SLOT).ifPresent(inventory::setSelectedSlot);

        root.getList(KEY_INVENTORY).ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i).orElse(new NbtCompound());
                int slot = entry.getInt(KEY_SLOT).orElse(-1);
                if (slot < 0 || slot >= inventory.size()) {
                    continue;
                }
                NbtElement stackElement = entry.get(KEY_STACK);
                if (stackElement == null) {
                    continue;
                }
                ItemStack stack = ItemStack.CODEC.parse(ops, stackElement)
                        .resultOrPartial(error -> LOGGER.warn("Failed to decode item for '{}': {}", bot.getName().getString(), error))
                        .orElse(ItemStack.EMPTY);
                inventory.setStack(slot, stack);
            }
        });

        inventory.markDirty();
        LOGGER.info("Loaded inventory for fakeplayer '{}' from {}", bot.getName().getString(), path.getFileName());
        return true;
    }

    private static Path resolveInventoryPath(MinecraftServer server, ServerPlayerEntity bot) {
        Path base = server.getSavePath(WorldSavePath.PLAYERDATA);
        if (base == null) {
            return null;
        }
        String alias = sanitize(bot.getGameProfile().name());
        String fileName = alias + "_" + bot.getUuidAsString() + ".nbt";
        return base.resolve("ai-player").resolve("inventories").resolve(fileName);
    }

    public static boolean delete(ServerPlayerEntity bot) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        Path path = resolveInventoryPath(server, bot);
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete inventory snapshot for '{}': {}", bot.getName().getString(), e.getMessage());
            return false;
        }
    }

    private static String sanitize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        return SAFE_NAME.matcher(normalized).replaceAll("_");
    }
}
