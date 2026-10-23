package net.wcfcarolina13.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotWorldStateService;
import net.wcfcarolina13.GameAI.services.BotFurnaceRegistryService;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.LegacyRegistryRecoveryPolicy;
import net.wcfcarolina13.GameAI.services.CraftingHistoryService;
import net.wcfcarolina13.GameAI.services.HuntHistoryService;
import net.wcfcarolina13.GameAI.services.HuntConfigService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.UnreadableStoreFile;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;

import java.util.concurrent.atomic.AtomicBoolean;

public final class LegacyWorldStateCommand {
    private static final AtomicBoolean NOTICES_REGISTERED = new AtomicBoolean();

    private LegacyWorldStateCommand() {}

    public static LiteralArgumentBuilder<ServerCommandSource> command() {
        return CommandManager.literal("legacy-state")
                .requires(Frens::isServerAdmin)
                .executes(context -> preview(context.getSource(), null))
                .then(CommandManager.literal("preview")
                        .then(CommandManager.argument("alias", StringArgumentType.string())
                                .executes(context -> preview(context.getSource(), alias(context)))))
                .then(CommandManager.literal("import")
                        .then(CommandManager.argument("alias", StringArgumentType.string())
                                .then(CommandManager.argument("fingerprint", StringArgumentType.string())
                                        .executes(LegacyWorldStateCommand::importState))))
                .then(registryCommand());
    }

    public static void registerNotices() {
        if (!NOTICES_REGISTERED.compareAndSet(false, true)) return;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            if (player instanceof createFakePlayer || BotEventHandler.isRegisteredBot(player)
                    || !Frens.isServerAdmin(player.getCommandSource())) return;
            var pending = BotWorldStateService.pendingLegacyAliases(server);
            if (!pending.isEmpty()) {
                player.sendMessage(Text.literal("Frens kept " + pending.size()
                        + " legacy companion position(s) separate from this save because their original save is uncertain. "
                        + "Use /bot legacy-state to preview and recover them. The originals are preserved."), false);
            }
            for (var world : server.getWorlds()) {
                for (String store : new String[]{"furnaces", "beehives", "crafting", "hunt", "fortifications", "villages", "hunt-config"}) {
                    var registry = registryPreview(store, world);
                    // Adopted at load by this save: the retained original needs no reminder.
                    if (registry.records() > 0 && !registry.adoptedHere()) {
                        player.sendMessage(Text.literal("Frens retained " + registry.records() + " original legacy " + store
                                + " record(s) for " + world.getRegistryKey().getValue()
                                + (registry.targetExists() ? ". This save already has its own registry, which will not be overwritten."
                                        : ". These have not been assigned to this save.")
                                + " Review them in that dimension with /bot legacy-state registry preview " + store + "."), false);
                    }
                }
            }
            // Store files that could not be read this game session (moved aside or left in place).
            // Last: the calls above are what first load the stores, so on a fresh launch the host's
            // only join would otherwise see an empty list.
            for (String line : UnreadableStoreFile.adminNotices()) {
                player.sendMessage(Text.literal(line), false);
            }
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> registryCommand() {
        return CommandManager.literal("registry")
                .then(CommandManager.literal("preview")
                        .then(CommandManager.argument("store", StringArgumentType.word())
                                .executes(context -> registry(context, false))))
                .then(CommandManager.literal("import")
                        .then(CommandManager.argument("store", StringArgumentType.word())
                                .then(CommandManager.argument("fingerprint", StringArgumentType.string())
                                        .executes(context -> registry(context, true))
                                        .then(CommandManager.literal("from")
                                                .then(CommandManager.argument("source", StringArgumentType.string())
                                                        .executes(context -> registry(context, true)))))));
    }

    private static int registry(CommandContext<ServerCommandSource> context, boolean importing) {
        var source = context.getSource();
        if (!Frens.isServerAdmin(source)) return refuse(source);
        String store = StringArgumentType.getString(context, "store");
        if (!java.util.Set.of("furnaces", "beehives", "crafting", "hunt", "fortifications", "villages", "hunt-config").contains(store)) {
            source.sendError(Text.literal("Choose furnaces, beehives, crafting, hunt, fortifications, villages or hunt-config."));
            return 0;
        }
        var world = source.getWorld();
        var preview = registryPreview(store, world);
        String fingerprint = BotWorldStateService.currentWorldKey(source.getServer());
        String file = switch (store) {
            case "furnaces" -> "bot_furnace_registry.json";
            case "beehives" -> "bot_beehive_registry.json";
            case "crafting" -> "crafting_history.json";
            case "hunt" -> "hunt_history.json";
            case "hunt-config" -> "hunt_config.json";
            case "fortifications" -> "bot_fortifications.json";
            default -> "mapped_villages.json";
        };
        source.sendFeedback(() -> Text.literal("Legacy " + store + " source: " + file + " key "
                + quote(preview.legacyKey()) + ". Target: " + quote(preview.targetKey())
                + ". Dimension: " + world.getRegistryKey().getValue() + ". Records: " + preview.records()), false);
        if (!importing) {
            for (String entry : preview.entries()) {
                source.sendFeedback(() -> Text.literal(entry), false);
            }
            String closing = LegacyRegistryRecoveryPolicy.previewClosingLine(preview.adoptedHere(),
                    preview.targetExists(), preview.records(), store, quote(fingerprint));
            source.sendFeedback(() -> Text.literal(closing), false);
            if (!preview.siblings().isEmpty()) {
                source.sendFeedback(() -> Text.literal("Other saves named " + quote(BotWorldStateService.currentLevelName(source.getServer()))
                        + " in this dimension (a moved or copied instance has a new fingerprint):"), false);
                for (String sibling : preview.siblings()) {
                    source.sendFeedback(() -> Text.literal(sibling), false);
                }
                source.sendFeedback(() -> Text.literal("To copy one into this save while it has no registry of its own, use /bot legacy-state registry import "
                        + store + " " + quote(fingerprint) + " from \"<key>\". The source is kept."), false);
            }
            return preview.records();
        }
        String confirmation = StringArgumentType.getString(context, "fingerprint");
        String sourceKey = hasArgument(context, "source") ? StringArgumentType.getString(context, "source") : null;
        var result = sourceKey != null ? importFromSibling(store, world, confirmation, sourceKey) : switch (store) {
            case "furnaces" -> BotFurnaceRegistryService.importLegacy(world, confirmation);
            case "beehives" -> BotBeehiveRegistryService.importLegacy(world, confirmation);
            case "crafting" -> CraftingHistoryService.importLegacy(world, confirmation);
            case "hunt" -> HuntHistoryService.importLegacy(world, confirmation);
            case "hunt-config" -> HuntConfigService.importLegacy(world, confirmation);
            case "fortifications" -> FortificationPersistenceService.importLegacy(world, confirmation);
            default -> MappedVillageService.importLegacy(world, confirmation);
        };
        String message = switch (result) {
            case IMPORTED -> "Copied " + store + " into " + quote(preview.targetKey())
                    + (sourceKey != null ? " from " + quote(sourceKey) + ". Source preserved." : ". Legacy originals preserved.");
            case TARGET_EXISTS -> "This save already has this registry. Nothing overwritten.";
            case NO_LEGACY -> sourceKey != null ? "No registry exists under that source key."
                    : "No matching legacy registry exists for this dimension.";
            case WRONG_CONFIRMATION -> "Save confirmation did not match. Preview the registry again.";
            case INVALID_TARGET -> "Cannot resolve a safe save fingerprint. Nothing imported.";
            case INVALID_SOURCE -> "That source is not another save with this name in this dimension. Preview the registry to list them.";
            case SAVE_FAILED -> "Registry recovery was not saved. Nothing imported. Original file retained. Check the server log.";
        };
        if (result == LegacyRegistryRecoveryPolicy.Result.IMPORTED) {
            source.sendFeedback(() -> Text.literal(message), false);
            return 1;
        }
        source.sendError(Text.literal(message));
        return 0;
    }

    private static LegacyRegistryRecoveryPolicy.Result importFromSibling(String store, ServerWorld world,
                                                                         String confirmation, String sourceKey) {
        return switch (store) {
            case "furnaces" -> BotFurnaceRegistryService.importFromSibling(world, confirmation, sourceKey);
            case "beehives" -> BotBeehiveRegistryService.importFromSibling(world, confirmation, sourceKey);
            case "crafting" -> CraftingHistoryService.importFromSibling(world, confirmation, sourceKey);
            case "hunt" -> HuntHistoryService.importFromSibling(world, confirmation, sourceKey);
            case "hunt-config" -> HuntConfigService.importFromSibling(world, confirmation, sourceKey);
            case "fortifications" -> FortificationPersistenceService.importFromSibling(world, confirmation, sourceKey);
            default -> MappedVillageService.importFromSibling(world, confirmation, sourceKey);
        };
    }

    private static boolean hasArgument(CommandContext<ServerCommandSource> context, String name) {
        try {
            StringArgumentType.getString(context, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static LegacyRegistryRecoveryPolicy.Preview registryPreview(String store, ServerWorld world) {
        return switch (store) {
            case "furnaces" -> BotFurnaceRegistryService.previewLegacy(world);
            case "beehives" -> BotBeehiveRegistryService.previewLegacy(world);
            case "crafting" -> CraftingHistoryService.previewLegacy(world);
            case "hunt" -> HuntHistoryService.previewLegacy(world);
            case "hunt-config" -> HuntConfigService.previewLegacy(world);
            case "fortifications" -> FortificationPersistenceService.previewLegacy(world);
            case "villages" -> MappedVillageService.previewLegacy(world);
            default -> throw new IllegalArgumentException("Unknown recovery store: " + store);
        };
    }

    private static int preview(ServerCommandSource source, String alias) {
        if (!Frens.isServerAdmin(source)) return refuse(source);
        var server = source.getServer();
        String target = BotWorldStateService.currentWorldKey(server);
        source.sendFeedback(() -> Text.literal("Legacy source: bot_world_state.json key "
                + quote(BotWorldStateService.currentLevelName(server)) + ". Current save: " + quote(target)), false);
        if (alias == null) {
            var pending = BotWorldStateService.pendingLegacyAliases(server);
            source.sendFeedback(() -> Text.literal(pending.isEmpty()
                    ? "No legacy positions need recovery in this save."
                    : "Awaiting explicit recovery: " + String.join(", ", pending.stream().limit(20).toList())
                            + (pending.size() > 20 ? " (" + pending.size() + " total)" : "")
                            + ". Inspect one with /bot legacy-state preview <alias>."), false);
            return pending.size();
        }
        var legacy = BotWorldStateService.legacyState(server, alias);
        if (legacy.isEmpty()) {
            source.sendError(Text.literal("No matching legacy position for " + quote(alias) + "."));
            return 0;
        }
        var state = legacy.get();
        source.sendFeedback(() -> Text.literal("Legacy " + quote(alias) + ": x=" + state.x()
                + ", y=" + state.y() + ", z=" + state.z() + ", yaw=" + state.yaw() + ", pitch=" + state.pitch()), false);
        if (BotWorldStateService.loadState(server, alias).isPresent()) {
            source.sendFeedback(() -> Text.literal("This save already has a position. It will not be overwritten. Legacy original retained."), false);
        } else {
            source.sendFeedback(() -> Text.literal("If this position belongs to this save, copy it with /bot legacy-state import "
                    + quote(alias) + " " + quote(target) + ". This saves a position only. No companion is spawned or moved."), false);
        }
        return 1;
    }

    private static int importState(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        if (!Frens.isServerAdmin(source)) return refuse(source);
        String alias = alias(context);
        String target = BotWorldStateService.currentWorldKey(source.getServer());
        var result = BotWorldStateService.importLegacyState(source.getServer(), alias,
                StringArgumentType.getString(context, "fingerprint"));
        String message = switch (result) {
            case IMPORTED -> "Copied legacy position for " + quote(alias) + " from "
                    + quote(BotWorldStateService.currentLevelName(source.getServer())) + " to " + quote(target)
                    + ". Original preserved. No companion spawned or moved.";
            case TARGET_EXISTS -> "This save already has a position for " + quote(alias) + ". Nothing overwritten.";
            case NO_LEGACY -> "No matching legacy position for " + quote(alias) + ".";
            case WRONG_CONFIRMATION -> "Save confirmation did not match. Preview again with /bot legacy-state preview " + quote(alias) + ".";
            case INVALID_TARGET -> "Cannot resolve a safe save fingerprint. Nothing imported.";
            case SAVE_FAILED -> "Could not persist the recovery. Nothing imported. The original file was retained. Check the server log.";
        };
        if (result == net.wcfcarolina13.GameAI.services.LegacyWorldStateRecoveryPolicy.Result.IMPORTED) {
            source.sendFeedback(() -> Text.literal(message), false);
            return 1;
        }
        source.sendError(Text.literal(message));
        return 0;
    }

    private static int refuse(ServerCommandSource source) {
        source.sendError(Text.literal("Only a server administrator can recover legacy companion positions."));
        return 0;
    }

    private static String alias(CommandContext<ServerCommandSource> context) {
        return StringArgumentType.getString(context, "alias");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
