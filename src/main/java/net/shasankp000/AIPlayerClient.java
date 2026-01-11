package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.DustParticleEffect;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.shasankp000.GraphicalUserInterface.BaseManagerScreen;
import net.shasankp000.GraphicalUserInterface.BotPlayerInventoryScreen;
import net.shasankp000.GraphicalUserInterface.CookablesScreen;
import net.shasankp000.GraphicalUserInterface.CraftingHistoryScreen;
import net.shasankp000.GraphicalUserInterface.ConfigManager;
import net.shasankp000.GraphicalUserInterface.HuntablesScreen;
import net.shasankp000.GraphicalUserInterface.CompanionSpellsScreen;
import net.shasankp000.GraphicalUserInterface.RecruitmentDialogueScreen;
import net.shasankp000.network.CookablesPayload;
import net.shasankp000.network.CraftingHistoryPayload;
import net.shasankp000.network.BasesListPayload;
import net.shasankp000.network.ConfigJsonUtil;
import net.shasankp000.network.ConfirmRecruitmentPayload;
import net.shasankp000.network.HuntablesPayload;
import net.shasankp000.network.OpenRecruitmentDialoguePayload;
import net.shasankp000.network.RecruitmentPromptPayload;
import net.shasankp000.network.RecruitmentStatePayload;
import net.shasankp000.network.RequestRecruitmentDialoguePayload;
import net.shasankp000.network.ResumeDecisionPayload;
import net.shasankp000.network.OpenConfigPayload;
import net.shasankp000.network.RecruitmentAdminStatusPayload;
import org.lwjgl.glfw.GLFW;

public class AIPlayerClient implements ClientModInitializer {

    private static KeyBinding KEY_FOLLOW_TOGGLE_LOOK;
    private static KeyBinding KEY_GO_TO_LOOK;
    private static KeyBinding KEY_RESUME;
    private static KeyBinding KEY_STOP_LOOK;
    private static KeyBinding KEY_LEASH;
    private static KeyBinding KEY_RECRUIT_CONTACT;

    // Pending shelter type from the Topics menu (null = no pending shelter, use go_to_look as normal)
    private static String pendingShelterType = null;
    private static String pendingShelterBotTarget = null;
    private static net.shasankp000.GameAI.schematic.SchematicData pendingSchematicData = null;

    private static boolean resumeDecisionActive = false;
    private static String resumeDecisionBotName = null;

    // Leash button state - shows when looking at a bot that has leashed animals
    private static boolean leashButtonVisible = false;
    private static String leashButtonBotName = null;
    // Mounted leash hint - shows when player is mounted and a nearby bot is also mounted
    private static boolean mountedLeashHintVisible = false;
    private static String mountedLeashBotName = null;

    // ===== Survival recruitment mode (client UI) =====
    private static boolean survivalRecruitmentEnabled = false;
    private static boolean survivalRecruitmentCompleted = false;
    private static boolean recruitmentPromptVisible = false;
    private static String recruitmentBotAlias = "Jake";

    // Auto-open recruitment dialogue once when entering a village (prompt transitions hidden -> visible).
    private static boolean recruitmentAutoOpenPending = false;
    private static boolean recruitmentAutoOpenedThisSession = false;

    // ===== Companion spells (client UX) =====
    // Client-side only (UX hint). Server remains authoritative.
    private static long eyeSpellCooldownUntilMs = 0L;

    // Simple per-bot dialogue log used by the Topics overlay.
    private static final java.util.Map<String, java.util.ArrayDeque<String>> DIALOGUE_LOG = new java.util.HashMap<>();

    // Server-authoritative companion quest state snapshot (for stage-gated dialogue topics).
    private static final java.util.Map<String, Integer> COMPANION_STAGE = new java.util.HashMap<>();
    private static final java.util.Set<String> COMPANION_PERMANENT = new java.util.HashSet<>();

    // Expose recruitment UI state to other client UI surfaces (e.g., bot inventory overlay).
    public static boolean isSurvivalRecruitmentEnabled() {
        return survivalRecruitmentEnabled;
    }

    public static boolean isSurvivalRecruitmentCompleted() {
        return survivalRecruitmentCompleted;
    }

    public static boolean isRecruitmentPromptVisible() {
        return recruitmentPromptVisible;
    }

    public static String getRecruitmentBotAlias() {
        return recruitmentBotAlias;
    }

    public static boolean isEyeSpellOnCooldown() {
        return getEyeSpellCooldownRemainingMs() > 0L;
    }

    public static long getEyeSpellCooldownRemainingMs() {
        long remaining = eyeSpellCooldownUntilMs - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public static void armEyeSpellCooldown() {
        // Keep aligned with server default (currently 60s). This is just a UX hint.
        eyeSpellCooldownUntilMs = System.currentTimeMillis() + 60_000L;
    }

    public static int getCompanionQuestStage(String botAlias) {
        if (botAlias == null || botAlias.isBlank()) {
            return -1;
        }
        Integer v = COMPANION_STAGE.get(botAlias.trim());
        return v != null ? v : -1;
    }

    public static boolean isCompanionPermanent(String botAlias) {
        if (botAlias == null || botAlias.isBlank()) {
            return false;
        }
        return COMPANION_PERMANENT.contains(botAlias.trim());
    }

    public static void applyCompanionQuestState(String botAlias, int stage, boolean permanent) {
        if (botAlias == null || botAlias.isBlank()) {
            return;
        }
        String key = botAlias.trim();
        COMPANION_STAGE.put(key, Math.max(0, stage));
        if (permanent) {
            COMPANION_PERMANENT.add(key);
        } else {
            COMPANION_PERMANENT.remove(key);
        }
    }

    public static void appendDialogue(String botAlias, String line) {
        if (botAlias == null || botAlias.isBlank() || line == null || line.isBlank()) {
            return;
        }
        String key = botAlias.trim();
        java.util.ArrayDeque<String> q = DIALOGUE_LOG.computeIfAbsent(key, ignored -> new java.util.ArrayDeque<>());
        q.addLast(line);
        while (q.size() > 48) {
            q.removeFirst();
        }
    }

    public static java.util.List<String> getDialogueLines(String botAlias, int maxLines) {
        if (botAlias == null || botAlias.isBlank()) {
            return java.util.List.of();
        }
        java.util.ArrayDeque<String> q = DIALOGUE_LOG.get(botAlias.trim());
        if (q == null || q.isEmpty()) {
            return java.util.List.of();
        }
        int limit = Math.max(1, maxLines);
        java.util.ArrayList<String> out = new java.util.ArrayList<>(Math.min(limit, q.size()));
        int skip = Math.max(0, q.size() - limit);
        int i = 0;
        for (String s : q) {
            if (i++ < skip) continue;
            out.add(s);
        }
        return out;
    }

    public static void setPendingShelter(String type, String botTarget) {
        pendingShelterType = type;
        pendingShelterBotTarget = botTarget;
        // Load the schematic data for preview
        if (type != null) {
            // First try loading from resources
            var schematicOpt = net.shasankp000.GameAI.schematic.SchematicReader.loadFromResources(type);
            if (schematicOpt.isPresent()) {
                pendingSchematicData = schematicOpt.get();
            } else {
                // Fall back to hard-coded dimensions for procedural shelters
                pendingSchematicData = getProceduralShelterDimensions(type);
            }
        } else {
            pendingSchematicData = null;
        }
    }
    
    /**
     * Get hard-coded dimensions for all structure types for particle preview.
     * These dimensions match the actual schematic sizes or are approximations.
     */
    private static net.shasankp000.GameAI.schematic.SchematicData getProceduralShelterDimensions(String type) {
        return switch (type.toLowerCase()) {
            // Procedural shelters
            case "hovel" -> new net.shasankp000.GameAI.schematic.SchematicData("hovel", 9, 5, 9, java.util.List.of(), java.util.List.of());
            case "burrow" -> new net.shasankp000.GameAI.schematic.SchematicData("burrow", 5, 8, 5, java.util.List.of(), java.util.List.of());
            // Schematic-based structures (get from SimpleSchematicBuilder if available)
            case "test_platform" -> getSchematicOrFallback("test_platform", 3, 1, 3);
            case "small_hut" -> getSchematicOrFallback("small_hut", 5, 4, 6);
            case "small_shelter" -> getSchematicOrFallback("small_shelter", 5, 5, 5);
            case "watchtower" -> getSchematicOrFallback("watchtower", 4, 9, 4);
            case "bridge" -> getSchematicOrFallback("bridge", 9, 3, 3);
            case "defensive_wall_section" -> getSchematicOrFallback("defensive_wall_section", 5, 5, 2);
            case "defensive_wall_corner" -> getSchematicOrFallback("defensive_wall_corner", 3, 6, 3);
            case "defensive_gatehouse" -> getSchematicOrFallback("defensive_gatehouse", 5, 5, 3);
            default -> null;
        };
    }
    
    /**
     * Try to get schematic from SimpleSchematicBuilder, fallback to dimensions if not available.
     */
    private static net.shasankp000.GameAI.schematic.SchematicData getSchematicOrFallback(String name, int x, int y, int z) {
        net.shasankp000.GameAI.schematic.SchematicData data = net.shasankp000.GameAI.schematic.SimpleSchematicBuilder.getBuiltIn(name);
        if (data != null) {
            return data;
        }
        return new net.shasankp000.GameAI.schematic.SchematicData(name, x, y, z, java.util.List.of(), java.util.List.of());
    }

    public static void clearPendingShelter() {
        pendingShelterType = null;
        pendingShelterBotTarget = null;
        pendingSchematicData = null;
    }
    
    public static boolean hasPendingShelter() {
        return pendingShelterType != null;
    }

    @Override
    public void onInitializeClient() {
        HandledScreens.register(AIPlayer.BOT_PLAYER_INV_HANDLER, BotPlayerInventoryScreen::new);

        // Keybind fallback for Shift+F1 / Shift+F2.
        // Notes:
        // - The mixin-based shortcut (Shift+F1/Shift+F2) still exists and suppresses vanilla F1/F2 side effects.
        // - These are regular keybinds (no modifier support), intended as a reliable, rebindable alternative
        //   especially on macOS where F-keys may be captured by the OS unless the user holds Fn or enables
        //   "Use F1, F2, etc. keys as standard function keys".
        // - Default: unbound, to avoid colliding with vanilla bindings.
        KEY_FOLLOW_TOGGLE_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.follow_toggle_look",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));
        KEY_GO_TO_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.go_to_look",
            // Default '-' as a single, memorable "context" key:
            // - before recruitment: opens recruitment dialogue
            // - after recruitment (when spells are available and companion is far away): opens spells
            // - otherwise: go-to-look
            GLFW.GLFW_KEY_MINUS,
            KeyBinding.Category.MISC
        ));

        KEY_RESUME = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.resume",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        KEY_STOP_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.stop_look",
            GLFW.GLFW_KEY_BACKSLASH,
            KeyBinding.Category.MISC
        ));

        KEY_LEASH = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.leash",
            GLFW.GLFW_KEY_APOSTROPHE,
            KeyBinding.Category.MISC
        ));

        KEY_RECRUIT_CONTACT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.recruit_contact",
            // Optional dedicated key; unbound by default (the go-to key provides the main context behavior).
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.getNetworkHandler() == null) {
                return;
            }
            if (client.currentScreen != null) {
                return;
            }

            // If the server just told us we're eligible (entered a village), auto-open the dialogue once.
            if (recruitmentAutoOpenPending) {
                if (survivalRecruitmentEnabled
                        && !survivalRecruitmentCompleted
                        && recruitmentPromptVisible
                        && !recruitmentAutoOpenedThisSession) {
                    ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
                    recruitmentAutoOpenedThisSession = true;
                }
                recruitmentAutoOpenPending = false;
            }

            if (KEY_FOLLOW_TOGGLE_LOOK.wasPressed()) {
                handleFollowToggleLookedAt(client);
            }
            if (KEY_GO_TO_LOOK.wasPressed()) {
                // Contextual behavior: before recruitment is complete and no companion is present,
                // reuse the user's go-to key to initiate recruitment dialogue (prevents keybind conflicts).
                if (shouldUseGoToKeyForRecruitmentContact(client)) {
                    handleRecruitContactKey(client);
                } else {
                    // After recruitment: if spells are available and the companion isn't currently present
                    // (far away / unloaded), override go-to with spells.
                    if (!isCompanionNearPlayer(client, 32.0D) && handleSpellsContextKey(client)) {
                        // Consumed.
                    } else {
                        handleGoToLook(client);
                    }
                }
            }
            if (KEY_RESUME.wasPressed()) {
                handleResumeKey(client);
            }
            if (KEY_STOP_LOOK.wasPressed()) {
                handleStopLook(client);
            }
            if (KEY_LEASH.wasPressed()) {
                handleLeashKey(client);
            }
            if (KEY_RECRUIT_CONTACT.wasPressed()) {
                // Context key (default '-'):
                // - During recruitment: initiates contact.
                // - After recruitment: opens companion spells when available.
                if (!handleSpellsContextKey(client)) {
                    handleRecruitContactKey(client);
                }
            }

            // Update leash button visibility based on whether we're looking at a bot with leashed animals
            updateLeashButtonVisibility(client);
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.ID, (payload, context) -> {
            ConfigJsonUtil.applyConfigJson(payload.configData());
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                Screen parent = client.currentScreen;
                client.setScreen(new ConfigManager(Text.literal("AI Player Configuration"), parent));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BasesListPayload.ID, (payload, context) -> {
            String json = payload.basesJson();
            context.client().execute(() -> BaseManagerScreen.applyBasesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(CraftingHistoryPayload.ID, (payload, context) -> {
            String json = payload.historyJson();
            context.client().execute(() -> CraftingHistoryScreen.applyHistoryJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(CookablesPayload.ID, (payload, context) -> {
            String json = payload.cookablesJson();
            context.client().execute(() -> CookablesScreen.applyCookablesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(HuntablesPayload.ID, (payload, context) -> {
            String json = payload.huntablesJson();
            context.client().execute(() -> HuntablesScreen.applyHuntablesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(ResumeDecisionPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                resumeDecisionActive = payload.active();
                resumeDecisionBotName = payload.active() ? payload.botName() : null;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                survivalRecruitmentEnabled = payload.enabled();
                survivalRecruitmentCompleted = payload.recruited();
                if (payload.botAlias() != null && !payload.botAlias().isBlank()) {
                    recruitmentBotAlias = payload.botAlias();
                }
                if (survivalRecruitmentCompleted) {
                    recruitmentPromptVisible = false;
                    recruitmentAutoOpenPending = false;
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.currentScreen instanceof RecruitmentDialogueScreen) {
                        client.setScreen(null);
                    }
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentPromptPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                boolean wasVisible = recruitmentPromptVisible;
                if (payload.botAlias() != null && !payload.botAlias().isBlank()) {
                    recruitmentBotAlias = payload.botAlias();
                }
                boolean dialogOpen = client != null && client.currentScreen instanceof RecruitmentDialogueScreen;
                if (dialogOpen) {
                    recruitmentPromptVisible = false;
                    recruitmentAutoOpenPending = false;
                    return;
                }
                recruitmentPromptVisible = payload.visible();
                if (survivalRecruitmentCompleted) {
                    recruitmentPromptVisible = false;
                }

                // Trigger auto-open only on the edge: hidden -> visible.
                if (!survivalRecruitmentCompleted
                        && survivalRecruitmentEnabled
                        && recruitmentPromptVisible
                        && !wasVisible
                        && !recruitmentAutoOpenedThisSession) {
                    recruitmentAutoOpenPending = true;
                }
                if (!recruitmentPromptVisible) {
                    recruitmentAutoOpenPending = false;
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenRecruitmentDialoguePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    return;
                }
                String alias = payload.botAlias();
                String flavor = payload.villageFlavor();
                client.setScreen(new RecruitmentDialogueScreen(alias, flavor));
                recruitmentPromptVisible = false;
                recruitmentAutoOpenPending = false;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(net.shasankp000.network.CompanionQuestResponsePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                String alias = payload.botAlias();
                String joined = payload.linesJoined();
                applyCompanionQuestState(alias, payload.stage(), payload.permanent());
                if (alias == null || alias.isBlank() || joined == null || joined.isBlank()) {
                    return;
                }
                // Each line is already "bot-authored" server-side; we prefix it with the bot alias here.
                String[] lines = joined.split("\\n");
                for (String line : lines) {
                    if (line != null && !line.isBlank()) {
                        appendDialogue(alias, alias + ": " + line);
                    }
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(net.shasankp000.network.CompanionQuestStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> applyCompanionQuestState(payload.botAlias(), payload.stage(), payload.permanent()));
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentAdminStatusPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                String alias = payload.botAlias();
                String joined = payload.linesJoined();
                if (alias == null || alias.isBlank() || joined == null || joined.isBlank()) {
                    return;
                }
                String[] lines = joined.split("\\n");
                for (String line : lines) {
                    if (line == null || line.isBlank()) continue;
                    appendDialogue(alias, "Admin: " + line);
                }
            });
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> renderResumeDecisionHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderLeashButton(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderRecruitmentPrompt(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderSpellsPrompt(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderSchematicPreview(context));
        
        // Update schematic preview box every client tick
        ClientTickEvents.END_CLIENT_TICK.register(AIPlayerClient::updateSchematicPreviewBox);
    }

    private static void handleRecruitContactKey(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        var player = client.player;
        if (player == null) {
            return;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted) {
            return;
        }
        // Always allow an attempt; the server remains authoritative and will respond with an explanation
        // (e.g., not in a village / mode disabled). This avoids a soft-lock if the prompt desyncs.
        if (!recruitmentPromptVisible) {
            player.sendMessage(Text.literal("You try to make contact..."), true);
        }
        ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
    }

    private static void renderRecruitmentPrompt(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted || !recruitmentPromptVisible) {
            return;
        }

        // Prefer showing the key that the player is most likely to have already bound:
        // their go-to key (which we treat as a contextual "contact" key when no companion is present).
        // Fall back to the dedicated recruit-contact binding if go-to is unbound.
        String keyName = resolveRecruitmentPromptKeyName();
        String line1 = "You sense someone watching...";
        String line2 = "Press [" + keyName + "] to initiate contact";

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = 12;
        int y = 10;
        int boxH = client.textRenderer.fontHeight * 2 + 6;

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    private static boolean handleSpellsContextKey(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen != null) {
            return false;
        }

        // Don't interfere with recruitment flow.
        if (survivalRecruitmentEnabled && !survivalRecruitmentCompleted) {
            return false;
        }
        // Spells are only meaningful after a companion exists.
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return false;
        }

        if (!canAccessCompanionSpells(client)) {
            return false;
        }

        // If Eye is the only access method and we are on cooldown, show an actionbar hint.
        if (isEyeOnlyAccess(client) && isEyeSpellOnCooldown()) {
            long sec = Math.max(1L, getEyeSpellCooldownRemainingMs() / 1000L);
            client.player.sendMessage(Text.literal("Eye of Ender spell is on cooldown (" + sec + "s)."), true);
            return true;
        }

        String alias = getRecruitmentBotAlias();
        client.setScreen(new CompanionSpellsScreen(null, alias));
        return true;
    }

    private static void renderSpellsPrompt(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return;
        }

        boolean anyAccess = isNearEnchantingTable(client, 4)
            || hasSpellbookToken(client)
            || hasEyeOfEnderToken(client)
            || hasGoatHornToken(client);
        if (!anyAccess) {
            return;
        }

        // If companion is already nearby, these remote spells are usually redundant.
        if (isCompanionNearPlayer(client, 32.0D)) {
            return;
        }

        // Prefer the go-to key (the one that actually overrides behavior), fall back to dedicated binding.
        String keyName = keyNameOrNull(KEY_GO_TO_LOOK);
        if (keyName == null) {
            keyName = keyNameOrNull(KEY_RECRUIT_CONTACT);
        }
        if (keyName == null) {
            keyName = "-";
        }

        String line1 = "You feel the tether pull...";
        String line2;
        boolean hasBook = hasSpellbookToken(client);
        boolean nearTable = isNearEnchantingTable(client, 4);
        boolean hasEye = hasEyeOfEnderToken(client);
        boolean hasHorn = hasGoatHornToken(client);
        boolean full = hasBook || nearTable;
        boolean eyePartial = hasEye && !full;
        boolean hornPartial = hasHorn && !full;

        if (eyePartial && hornPartial) {
            if (isEyeSpellOnCooldown()) {
                long sec = Math.max(1L, getEyeSpellCooldownRemainingMs() / 1000L);
                line2 = "Press [" + keyName + "] for spells (Horn: come, Eye cooldown " + sec + "s)";
            } else {
                line2 = "Press [" + keyName + "] for spells (Horn: come, Eye: summon-only)";
            }
        } else if (hornPartial) {
            line2 = "Press [" + keyName + "] for spells (Goat Horn: come-only)";
        } else if (eyePartial) {
            if (isEyeSpellOnCooldown()) {
                long sec = Math.max(1L, getEyeSpellCooldownRemainingMs() / 1000L);
                line2 = "Press [" + keyName + "] for spells (Eye cooldown " + sec + "s)";
            } else {
                line2 = "Press [" + keyName + "] for spells (Eye: summon-only)";
            }
        } else if (hasBook) {
            line2 = "Press [" + keyName + "] for spells (Spellbook)";
        } else if (nearTable) {
            line2 = "Press [" + keyName + "] for spells (Enchanting Table)";
        } else {
            line2 = "Press [" + keyName + "] for spells";
        }

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = 12;
        // Stack beneath the recruitment prompt area.
        int y = 10 + (client.textRenderer.fontHeight * 2 + 10);
        int boxH = client.textRenderer.fontHeight * 2 + 6;

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    private static boolean canAccessCompanionSpells(MinecraftClient client) {
        return hasSpellbookToken(client)
                || isNearEnchantingTable(client, 4)
                || hasEyeOfEnderToken(client)
                || hasGoatHornToken(client);
    }

    private static boolean isEyeOnlyAccess(MinecraftClient client) {
        boolean full = hasSpellbookToken(client) || isNearEnchantingTable(client, 4);
        boolean horn = !full && hasGoatHornToken(client);
        return !full && !horn && hasEyeOfEnderToken(client);
    }

    private static boolean isNearEnchantingTable(MinecraftClient client, int radius) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        BlockPos origin = client.player.getBlockPos();
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!client.world.isChunkLoaded(pos)) {
                continue;
            }
            var state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpellbookToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!(stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.ENCHANTED_BOOK))) {
                continue;
            }
            String name = stack.getName() != null ? stack.getName().getString() : "";
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains("spellbook")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEyeOfEnderToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGoatHornToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.GOAT_HORN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldUseGoToKeyForRecruitmentContact(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted) {
            return false;
        }
        if (!recruitmentPromptVisible) {
            return false;
        }
        // If the recruited companion is already nearby, don't steal the go-to key.
        return !isCompanionNearPlayer(client, 32.0D);
    }

    private static boolean isCompanionNearPlayer(MinecraftClient client, double maxDistanceBlocks) {
        if (client == null || client.player == null) {
            return false;
        }
        var world = client.world;
        if (world == null) {
            return false;
        }
        if (recruitmentBotAlias == null || recruitmentBotAlias.isBlank()) {
            return false;
        }
        String alias = recruitmentBotAlias.trim();
        double maxSq = maxDistanceBlocks * maxDistanceBlocks;
        try {
            for (PlayerEntity p : world.getPlayers()) {
                if (p == null || p == client.player) {
                    continue;
                }
                String name = p.getName() != null ? p.getName().getString() : null;
                if (name != null && name.equalsIgnoreCase(alias)) {
                    // "Near" is defined by distance, not mere client-side existence.
                    return p.squaredDistanceTo(client.player) <= maxSq;
                }
            }
        } catch (Throwable ignored) {
            // Best effort; if something changes in mappings, just don't gate on it.
        }
        return false;
    }

    private static boolean isCompanionPresentInClientWorld(MinecraftClient client) {
        if (client == null) {
            return false;
        }
        var world = client.world;
        if (world == null) {
            return false;
        }
        if (recruitmentBotAlias == null || recruitmentBotAlias.isBlank()) {
            return false;
        }
        String alias = recruitmentBotAlias.trim();
        try {
            for (PlayerEntity p : world.getPlayers()) {
                if (p == null || p == client.player) {
                    continue;
                }
                String name = p.getName() != null ? p.getName().getString() : null;
                if (name != null && name.equalsIgnoreCase(alias)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Best effort; if something changes in mappings, just don't gate on it.
        }
        return false;
    }

    private static String resolveRecruitmentPromptKeyName() {
        String goTo = keyNameOrNull(KEY_GO_TO_LOOK);
        if (goTo != null) {
            return goTo;
        }
        String recruit = keyNameOrNull(KEY_RECRUIT_CONTACT);
        if (recruit != null) {
            return recruit;
        }
        return "?";
    }

    private static String keyNameOrNull(KeyBinding binding) {
        if (binding == null) {
            return null;
        }
        try {
            String name = binding.getBoundKeyLocalizedText().getString();
            if (name == null || name.isBlank()) {
                return null;
            }
            // Heuristic: treat common "unbound" labels as unavailable.
            if ("unknown".equalsIgnoreCase(name) || "unassigned".equalsIgnoreCase(name)) {
                return null;
            }
            return name;
        } catch (Throwable ignored) {
            return null;
        }
    }
    
    /**
     * Called every client tick to render the schematic preview overlay.
     */
    private static void renderSchematicPreviewTick(MinecraftClient client) {
        if (pendingSchematicData == null || pendingShelterType == null) {
            return;
        }
        if (client == null || client.player == null || client.world == null || client.currentScreen != null) {
            return;
        }
        // Actual rendering will be done using debug renderer or a custom approach
        // For now, just ensure the preview state is tracked
    }

    private static void handleGoToLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (resumeDecisionActive) {
            sendResumeDecision(client, false);
            return;
        }
        // Check if there's a pending shelter command from the Topics menu
        if (pendingShelterType != null) {
            String cmd;
            // Determine if this is a procedural shelter or schematic build
            if (pendingShelterType.equals("hovel") || pendingShelterType.equals("burrow")) {
                cmd = "bot shelter_look " + pendingShelterType;
                if (pendingShelterBotTarget != null && !pendingShelterBotTarget.isEmpty()) {
                    cmd += " " + pendingShelterBotTarget;
                }
            } else {
                // For schematic builds, use build_look to respect looked-at position
                cmd = "bot build_look " + pendingShelterType;
                if (pendingShelterBotTarget != null && !pendingShelterBotTarget.isEmpty()) {
                    cmd += " " + pendingShelterBotTarget;
                }
            }
            
            sendChatCommand(client, cmd);
            clearPendingShelter();
        } else {
            // Normal go_to_look behavior
            sendChatCommand(client, "bot go_to_look");
        }
    }

    private static void handleFollowToggleLookedAt(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (resumeDecisionActive) {
            sendResumeDecision(client, true);
            return;
        }
        String name = findLookedAtBotName(client);
        if (name == null || name.isBlank()) {
            client.player.sendMessage(Text.literal("Look at a bot to toggle follow (within 16 blocks)."), true);
            return;
        }
        sendChatCommand(client, "bot follow toggle " + name);
    }

    private static void sendChatCommand(MinecraftClient client, String command) {
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(raw);
    }

    private static void handleResumeKey(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (!resumeDecisionActive) {
            return;
        }
        sendResumeDecision(client, true);
    }

    private static void handleStopLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        
        // If there's a pending shelter placement, cancel it first
        if (pendingShelterType != null) {
            clearPendingShelter();
            client.player.sendMessage(Text.literal("Canceled construction placement."), true);
            return;
        }
        
        String name = findLookedAtBotName(client);
        if (name == null || name.isBlank()) {
            client.player.sendMessage(Text.literal("Look at a bot to stop it (within 16 blocks)."), true);
            return;
        }
        sendChatCommand(client, "bot stop " + name);
    }

    private static String findLookedAtBotName(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        final double maxDistance = 16.0;
        net.minecraft.util.math.Vec3d eyePos = client.player.getEyePos();
        net.minecraft.util.math.Vec3d lookVec = client.player.getRotationVec(1.0F);
        net.minecraft.util.math.Vec3d rayEnd = eyePos.add(lookVec.multiply(maxDistance));

        PlayerEntity foundBot = null;
        double closestDist = maxDistance;

        for (net.minecraft.entity.Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity target) || target == client.player) {
                continue;
            }
            if (target.squaredDistanceTo(client.player) > maxDistance * maxDistance) {
                continue;
            }
            net.minecraft.util.math.Box entityBox = target.getBoundingBox().expand(0.3);
            java.util.Optional<net.minecraft.util.math.Vec3d> intersect = entityBox.raycast(eyePos, rayEnd);
            if (intersect.isPresent()) {
                double dist = eyePos.squaredDistanceTo(intersect.get());
                if (dist < closestDist * closestDist) {
                    closestDist = Math.sqrt(dist);
                    foundBot = target;
                }
            }
        }

        if (foundBot == null) {
            return null;
        }
        String name = foundBot.getName().getString();
        return name != null && !name.isBlank() ? name : null;
    }

    private static String resolveDecisionBotName(MinecraftClient client) {
        if (resumeDecisionBotName != null && !resumeDecisionBotName.isBlank()) {
            return resumeDecisionBotName;
        }
        return findLookedAtBotName(client);
    }

    private static void sendResumeDecision(MinecraftClient client, boolean resume) {
        String botName = resolveDecisionBotName(client);
        String command;
        if (botName != null && !botName.isBlank()) {
            command = resume ? ("bot resume " + botName) : ("bot stop " + botName);
        } else {
            command = resume ? "bot resume" : "bot stop";
        }
        sendChatCommand(client, command);
        resumeDecisionActive = false;
        resumeDecisionBotName = null;
    }

    private static void renderResumeDecisionHint(DrawContext context) {
        if (!resumeDecisionActive) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        String target = resumeDecisionBotName != null && !resumeDecisionBotName.isBlank()
                ? resumeDecisionBotName
                : "bot";
        String line1 = "Resume/Stop pending for " + target;
        String line2 = "Follow key: Resume  |  Go-To-Look key: Stop";
        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int x = (context.getScaledWindowWidth() - Math.max(w1, w2)) / 2;
        int y = 10;
        context.fill(x - 6, y - 4, x + Math.max(w1, w2) + 6, y + client.textRenderer.fontHeight * 2 + 6, 0xAA101010);
        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    // ===== Leash Button Feature =====

    /**
     * Update the visibility state of the leash button and mounted leash hint.
     * Called every tick to check if we're looking at a bot that has leashed animals,
     * or if the player is mounted near a mounted bot.
     */
    private static void updateLeashButtonVisibility(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            leashButtonVisible = false;
            leashButtonBotName = null;
            mountedLeashHintVisible = false;
            mountedLeashBotName = null;
            return;
        }

        // Reset state each tick.
        leashButtonVisible = false;
        leashButtonBotName = null;
        mountedLeashHintVisible = false;
        mountedLeashBotName = null;

        // Case 1: Looking at a bot with leashed animals.
        String botName = findLookedAtBotName(client);
        if (botName != null && !botName.isBlank()) {
            PlayerEntity botEntity = null;
            for (Entity entity : client.world.getEntities()) {
                if (entity instanceof PlayerEntity player && player != client.player) {
                    if (botName.equals(player.getName().getString())) {
                        botEntity = player;
                        break;
                    }
                }
            }
            if (botEntity != null) {
                for (Entity entity : client.world.getEntities()) {
                    if (entity instanceof MobEntity mob) {
                        if (!mob.isAlive()) continue;
                        if (!mob.isLeashed()) continue;
                        if (mob.squaredDistanceTo(botEntity) > 256.0) continue;
                        Entity holder = mob.getLeashHolder();
                        if (holder != null && holder == botEntity) {
                            leashButtonVisible = true;
                            leashButtonBotName = botName;
                            return; // Prefer this hint when we're looking at a bot with leashed animals.
                        }
                    }
                }
            }
        }

        // Case 2: Player is mounted and there's a nearby mounted bot.
        if (client.player.hasVehicle()) {
            String mountedBotName = findNearbyMountedBotName(client);
            if (mountedBotName != null) {
                mountedLeashHintVisible = true;
                mountedLeashBotName = mountedBotName;
            }
        }
    }

    /**
     * Handle the leash keybind press.
     * - If we're looking at a bot with leashed animals: send /bot leash to tie them to a fence.
     * - If the player is mounted: find a nearby mounted bot and tell it to dismount and tether.
     */
    private static void handleLeashKey(MinecraftClient client) {
        System.out.println("[AI-Player] handleLeashKey called");
        if (client == null || client.player == null || client.world == null) {
            System.out.println("[AI-Player] handleLeashKey: client/player/world is null");
            return;
        }

        // Case 1: Looking at a bot with leashed animals - use existing behavior.
        if (leashButtonVisible && leashButtonBotName != null) {
            System.out.println("[AI-Player] handleLeashKey: Case 1 - leashing " + leashButtonBotName);
            sendChatCommand(client, "bot leash " + leashButtonBotName);
            return;
        }

        // Case 2: Player is mounted - find a nearby mounted bot and tell it to dismount and tether.
        if (client.player.hasVehicle()) {
            String mountedBotName = findNearbyMountedBotName(client);
            System.out.println("[AI-Player] handleLeashKey: Case 2 - player mounted, nearby bot: " + mountedBotName);
            if (mountedBotName != null) {
                // First stop/dismount the bot, then run leash skill to tether the horse.
                sendChatCommand(client, "bot stop " + mountedBotName);
                // Small delay before leash command - we send it immediately, the server will handle sequencing.
                sendChatCommand(client, "bot leash " + mountedBotName);
                client.player.sendMessage(Text.literal("Telling " + mountedBotName + " to dismount and tether."), true);
                return;
            } else {
                client.player.sendMessage(Text.literal("No nearby mounted bot found."), true);
                return;
            }
        }

        // Case 3: Not looking at a bot with leashed animals, not mounted.
        System.out.println("[AI-Player] handleLeashKey: Case 3 - showing hint message");
        client.player.sendMessage(Text.literal("Look at a bot with leashed animals, or be mounted near a mounted bot."), true);
    }

    /**
     * Find a nearby bot (player entity that isn't the client player) that is mounted.
     * Returns the bot's name, or null if none found.
     */
    private static String findNearbyMountedBotName(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        final double maxDistance = 32.0;
        PlayerEntity closest = null;
        double closestDistSq = maxDistance * maxDistance;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity target) || target == client.player) {
                continue;
            }
            if (!target.hasVehicle()) {
                continue;
            }
            double distSq = target.squaredDistanceTo(client.player);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = target;
            }
        }

        if (closest != null) {
            String name = closest.getName().getString();
            return (name != null && !name.isBlank()) ? name : null;
        }
        return null;
    }

    /**
     * Render the leash button HUD element when visible.
     * Shows at the top of the screen when looking at a bot with leashed animals,
     * or when mounted near a mounted bot.
     */
    private static void renderLeashButton(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        String line1 = null;
        String line2 = null;

        if (leashButtonVisible && leashButtonBotName != null) {
            line1 = "🐴 " + leashButtonBotName + " is leading animals";
            line2 = "Press ['] to tie to nearby fence";
        } else if (mountedLeashHintVisible && mountedLeashBotName != null) {
            line1 = "🐴 " + mountedLeashBotName + " is riding alongside";
            line2 = "Press ['] to dismount & tether";
        }

        if (line1 == null || line2 == null) {
            return;
        }

        // Don't overlap with resume decision hint
        int yOffset = resumeDecisionActive ? 45 : 10;

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = (context.getScaledWindowWidth() - maxW) / 2;
        int y = yOffset;
        int boxHeight = client.textRenderer.fontHeight * 2 + 6;

        // Draw a semi-transparent dark background
        context.fill(x - 6, y - 4, x + maxW + 6, y + boxHeight, 0xAA2a4a2a);
        // Border for visibility
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4a8a4a);
        context.fill(x - 7, y + boxHeight, x + maxW + 7, y + boxHeight + 1, 0xFF4a8a4a);
        context.fill(x - 7, y - 5, x - 6, y + boxHeight + 1, 0xFF4a8a4a);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxHeight + 1, 0xFF4a8a4a);

        // Draw the text
        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFB8E6B8);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFF90C890);
    }

    // Store the preview box to be rendered
    private static Box pendingPreviewBox = null;
    private static Box previousPreviewBox = null;
    private static float previousPlayerYaw = 0;
    private static float previousPlayerPitch = 0;
    private static int particleSpawnTick = 0;
    private static int particlePauseTicks = 0; // Pause spawning when preview moves to let old particles fade
    
    /**
     * Updates the schematic preview box every client tick based on player's look direction.
     */
    private static void updateSchematicPreviewBox(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || pendingSchematicData == null) {
            pendingPreviewBox = null;
            return;
        }
        
        // Don't show preview if a screen is open
        if (client.currentScreen != null) {
            pendingPreviewBox = null;
            return;
        }
        
        // Raycast to find where the player is looking (max 32 blocks)
        HitResult hit = client.player.raycast(32.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            pendingPreviewBox = null;
            return;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos hitBlockPos = blockHit.getBlockPos();
        
        // Check if the hit block is replaceable (snow, carpet, grass, etc.)
        // For replaceable blocks, we build AT that position, not above it
        net.minecraft.block.BlockState hitState = client.world.getBlockState(hitBlockPos);
        boolean isReplaceable = hitState.isReplaceable() 
            || hitState.isOf(net.minecraft.block.Blocks.SNOW)
            || hitState.isOf(net.minecraft.block.Blocks.SHORT_GRASS)
            || hitState.isOf(net.minecraft.block.Blocks.TALL_GRASS)
            || hitState.isOf(net.minecraft.block.Blocks.FERN);
        
        int baseX = hitBlockPos.getX();
        int baseY = hitBlockPos.getY();
        int baseZ = hitBlockPos.getZ();
        
        // If hit from above and block is NOT replaceable, place on top of the block
        // For replaceable blocks (snow layers, grass, etc.), place AT that Y level
        if (blockHit.getSide() == Direction.UP && !isReplaceable) {
            baseY += 1;
        }
        
        // Get schematic dimensions (use actual dimensions - schematics are not rotated)
        int sizeX = pendingSchematicData.sizeX();
        int sizeY = pendingSchematicData.sizeY();
        int sizeZ = pendingSchematicData.sizeZ();
        
        // Center preview on cursor and offset 3 blocks forward along look direction
        float yaw = client.player.getYaw();
        double yawRad = Math.toRadians(yaw);
        int forwardOffsetX = (int) Math.round(-Math.sin(yawRad) * 3.0);
        int forwardOffsetZ = (int) Math.round(Math.cos(yawRad) * 3.0);
        
        // Center the box with forward offset (using actual schematic dimensions)
        int offsetX = -sizeX / 2 + forwardOffsetX;
        int offsetZ = -sizeZ / 2 + forwardOffsetZ;
        
        // Preview box centered on cursor with forward offset
        pendingPreviewBox = new Box(
            baseX + offsetX, baseY, baseZ + offsetZ,
            baseX + offsetX + sizeX, baseY + sizeY, baseZ + offsetZ + sizeZ
        );
    }
    
    /**
     * Renders the schematic preview box using particles along the edges.
     * Spawns green particles to show where the structure will be placed.
     */
    private static void renderSchematicPreview(DrawContext context) {
        if (pendingPreviewBox == null) {
            previousPreviewBox = null;
            particlePauseTicks = 0;
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        
        // Check if player view direction changed significantly (more than 2 degrees)
        float yawDiff = Math.abs(client.player.getYaw() - previousPlayerYaw);
        float pitchDiff = Math.abs(client.player.getPitch() - previousPlayerPitch);
        
        if (yawDiff > 2.0f || pitchDiff > 2.0f) {
            // View direction changed - clear preview and pause spawning to let old particles fade
            previousPreviewBox = null;
            previousPlayerYaw = client.player.getYaw();
            previousPlayerPitch = client.player.getPitch();
            particleSpawnTick = 0;
            particlePauseTicks = 10; // Pause 10 ticks (0.5 seconds) to let old particles fade
            return;
        }
        
        // If we're in pause mode after a view change, count down and skip spawning
        if (particlePauseTicks > 0) {
            particlePauseTicks--;
            return;
        }
        
        // Check if the preview box has moved (any movement at all)
        if (previousPreviewBox != null) {
            double dx = Math.abs(pendingPreviewBox.minX - previousPreviewBox.minX);
            double dy = Math.abs(pendingPreviewBox.minY - previousPreviewBox.minY);
            double dz = Math.abs(pendingPreviewBox.minZ - previousPreviewBox.minZ);
            
            if (dx > 0.1 || dy > 0.1 || dz > 0.1) {
                // Preview moved - clear it and pause spawning to let old particles fade
                previousPreviewBox = null;
                particleSpawnTick = 0;
                particlePauseTicks = 6; // Pause 6 ticks (0.3 seconds)
                return;
            }
        }
        
        // Update previous box and angles for next frame
        previousPreviewBox = pendingPreviewBox;
        previousPlayerYaw = client.player.getYaw();
        previousPlayerPitch = client.player.getPitch();
        
        // Only spawn particles every 3 ticks for balanced density
        particleSpawnTick++;
        if (particleSpawnTick < 3) {
            return;
        }
        particleSpawnTick = 0;
        
        // Get box dimensions
        double minX = pendingPreviewBox.minX;
        double minY = pendingPreviewBox.minY;
        double minZ = pendingPreviewBox.minZ;
        double maxX = pendingPreviewBox.maxX;
        double maxY = pendingPreviewBox.maxY;
        double maxZ = pendingPreviewBox.maxZ;
        
        // Create green tinted dust particle (like green stained glass)
        // Pack RGB into int: 0x33CC4D = (51, 204, 77) = green tint
        // DustParticleEffect(int color, float scale) in MC 1.21+
        int greenColor = 0x33CC4D; // RGB green
        DustParticleEffect greenDust = new DustParticleEffect(greenColor, 1.5f);
        
        // Spacing for face particles (1 block apart for less clutter)
        double faceSpacing = 1.0;
        
        // Bottom face (floor outline) - most important for placement
        spawnParticlesOnFace(client, greenDust, minX, minY, minZ, maxX, minY, maxZ, faceSpacing, true);
        
        // Top face (roof outline)
        spawnParticlesOnFace(client, greenDust, minX, maxY, minZ, maxX, maxY, maxZ, faceSpacing, true);
        
        // Front face (minZ) - vertical wall
        spawnParticlesOnFace(client, greenDust, minX, minY, minZ, maxX, maxY, minZ, faceSpacing, false);
        
        // Back face (maxZ) - vertical wall  
        spawnParticlesOnFace(client, greenDust, minX, minY, maxZ, maxX, maxY, maxZ, faceSpacing, false);
        
        // Left face (minX) - vertical wall
        spawnParticlesOnFaceZ(client, greenDust, minX, minY, minZ, minX, maxY, maxZ, faceSpacing);
        
        // Right face (maxX) - vertical wall
        spawnParticlesOnFaceZ(client, greenDust, maxX, minY, minZ, maxX, maxY, maxZ, faceSpacing);
    }
    
    /**
     * Spawns particles across a horizontal or XY vertical face.
     */
    private static void spawnParticlesOnFace(MinecraftClient client, DustParticleEffect particle,
                                              double x1, double y1, double z1,
                                              double x2, double y2, double z2,
                                              double spacing, boolean horizontal) {
        int stepsX = (int) Math.ceil(Math.abs(x2 - x1) / spacing);
        int stepsY = horizontal ? 0 : (int) Math.ceil(Math.abs(y2 - y1) / spacing);
        int stepsZ = horizontal ? (int) Math.ceil(Math.abs(z2 - z1) / spacing) : 0;
        
        if (horizontal) {
            // Horizontal face (floor/ceiling) - fill X and Z
            for (int i = 0; i <= stepsX; i++) {
                for (int j = 0; j <= stepsZ; j++) {
                    double x = x1 + (x2 - x1) * i / Math.max(1, stepsX);
                    double z = z1 + (z2 - z1) * j / Math.max(1, stepsZ);
                    client.particleManager.addParticle(particle, x, y1, z, 0.0, 0.0, 0.0);
                }
            }
        } else {
            // Vertical face (XY plane) - fill X and Y
            for (int i = 0; i <= stepsX; i++) {
                for (int j = 0; j <= stepsY; j++) {
                    double x = x1 + (x2 - x1) * i / Math.max(1, stepsX);
                    double y = y1 + (y2 - y1) * j / Math.max(1, stepsY);
                    client.particleManager.addParticle(particle, x, y, z1, 0.0, 0.0, 0.0);
                }
            }
        }
    }
    
    /**
     * Spawns particles across a ZY vertical face (constant X).
     */
    private static void spawnParticlesOnFaceZ(MinecraftClient client, DustParticleEffect particle,
                                               double x, double y1, double z1,
                                               double x2, double y2, double z2,
                                               double spacing) {
        int stepsZ = (int) Math.ceil(Math.abs(z2 - z1) / spacing);
        int stepsY = (int) Math.ceil(Math.abs(y2 - y1) / spacing);
        
        for (int i = 0; i <= stepsZ; i++) {
            for (int j = 0; j <= stepsY; j++) {
                double z = z1 + (z2 - z1) * i / Math.max(1, stepsZ);
                double y = y1 + (y2 - y1) * j / Math.max(1, stepsY);
                client.particleManager.addParticle(particle, x, y, z, 0.0, 0.0, 0.0);
            }
        }
    }
}
