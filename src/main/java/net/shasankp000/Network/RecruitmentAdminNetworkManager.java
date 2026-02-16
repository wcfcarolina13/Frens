package net.shasankp000.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.AIPlayer;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.services.SurvivalRecruitmentService;
import net.shasankp000.GameAI.services.WizardTomeGrantService;
import net.shasankp000.network.CompanionQuestStatePayload;
import net.shasankp000.network.RecruitmentPromptPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Server-side operator-only actions for survival recruitment state. */
public final class RecruitmentAdminNetworkManager {

    private static volatile boolean REGISTERED = false;

    private RecruitmentAdminNetworkManager() {
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RecruitmentAdminActionPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, RecruitmentAdminActionPayload payload) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        // Only real players can use admin actions.
        if (player instanceof createFakePlayer) {
            return;
        }

        String botAlias = payload != null && payload.botAlias() != null && !payload.botAlias().isBlank()
                ? payload.botAlias().trim()
                : "Jake";

        if (!AIPlayer.isOperator(player)) {
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Not authorized."));
            return;
        }

        if (AIPlayer.CONFIG == null) {
            ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, "Config not ready."));
            return;
        }

        String action = payload != null && payload.action() != null ? payload.action().trim().toLowerCase(Locale.ROOT) : "";
        String worldKey = worldKey(server);
        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);

        List<String> out = new ArrayList<>();

        switch (action) {
            case "give_wizard_tome" -> {
                int granted = WizardTomeGrantService.grant(player, Math.max(1, payload.intArg()));
                if (granted <= 0) {
                    out.add("Could not grant Wizard's Tome (player unavailable).");
                } else {
                    out.add("Gave Wizard's Tome x" + granted + ".");
                }
            }
            case "status" -> {
                out.addAll(buildStatusLines(server, worldKey, st));
            }
            case "setanchor_here" -> {
                if (!SurvivalRecruitmentService.isEnabled(server)) {
                    out.add("Survival recruitment mode is disabled.");
                    out.add("Enable it first if you want the questline to use an anchor.");
                    break;
                }

                st.setCompanionAnchorSet(true);
                st.setCompanionAnchorDimension(player.getCommandSource().getWorld().getRegistryKey().getValue().toString());
                st.setCompanionAnchorPos(player.getBlockPos().asLong());
                AIPlayer.CONFIG.save();

                BlockPos p = player.getBlockPos();
                out.add("Anchor set here: " + p.getX() + "," + p.getY() + "," + p.getZ());
                out.add("Dim: " + st.getCompanionAnchorDimension());
            }
            case "clearanchor" -> {
                st.setCompanionAnchorSet(false);
                st.setCompanionAnchorDimension(null);
                st.setCompanionAnchorPos(0L);
                AIPlayer.CONFIG.save();
                out.add("Anchor cleared.");
            }
            case "reset" -> {
                st.setRecruited(false);
                st.setRecruitedByUuid(null);
                st.setRecruitedByName(null);
                st.setRecruitedAtEpochMs(0L);

                st.setCompanionQuestStage(0);
                st.setPermanentCompanion(false);
                st.setCompanionAnchorSet(false);
                st.setCompanionAnchorDimension(null);
                st.setCompanionAnchorPos(0L);

                AIPlayer.CONFIG.save();

                // Push updated recruitment state + quest state to all real players.
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                    ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, st.getBotAlias()));
                    ServerPlayNetworking.send(p, new CompanionQuestStatePayload(st.getBotAlias(), 0, false));
                }

                out.add("Recruitment reset for world '" + (worldKey == null ? "default" : worldKey) + "'.");
                out.add("Recruited=false; stage=0; permanent=false; anchor cleared.");
            }
            case "enable" -> {
                AIPlayer.CONFIG.setSurvivalRecruitmentMode(true);
                AIPlayer.CONFIG.save();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                }
                out.add("Survival recruitment mode: enabled");
            }
            case "disable" -> {
                AIPlayer.CONFIG.setSurvivalRecruitmentMode(false);
                AIPlayer.CONFIG.save();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    SurvivalRecruitmentService.sendRecruitmentState(p);
                    ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, st.getBotAlias()));
                }
                out.add("Survival recruitment mode: disabled");
            }
            case "setstage" -> {
                if (!SurvivalRecruitmentService.isEnabled(server)) {
                    out.add("Survival recruitment mode is disabled.");
                    out.add("Use 'Enable' first.");
                    break;
                }
                if (st == null || !st.isRecruited()) {
                    out.add("World is not recruited yet.");
                    break;
                }

                int stage = payload.intArg();
                st.setCompanionQuestStage(stage);
                // Keep consistent with SurvivalCompanionQuestService:
                // stage >= 4 means the companion becomes permanent.
                if (stage >= 4) {
                    st.setPermanentCompanion(true);
                    st.setCompanionQuestStage(Math.max(st.getCompanionQuestStage(), 4));
                } else {
                    st.setPermanentCompanion(false);
                }
                AIPlayer.CONFIG.save();

                int updatedStage = st.getCompanionQuestStage();
                boolean perm = st.isPermanentCompanion();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                        continue;
                    }
                    ServerPlayNetworking.send(p, new CompanionQuestStatePayload(st.getBotAlias(), updatedStage, perm));
                }

                out.add("Companion quest stage set to " + updatedStage + " (permanent=" + perm + ").");
                if (perm) {
                    out.add("Permanent companion unlocked (required for companion spells/commands).");
                }
            }
            default -> {
                out.add("Unknown action: '" + action + "'.");
            }
        }

        if (out.isEmpty()) {
            out.add("OK.");
        }

        ServerPlayNetworking.send(player, new RecruitmentAdminStatusPayload(botAlias, String.join("\n", out)));
    }

    private static String worldKey(MinecraftServer server) {
        if (server == null) {
            return "default";
        }
        String key = server.getSaveProperties().getLevelName();
        return (key == null || key.isBlank()) ? "default" : key;
    }

    private static List<String> buildStatusLines(MinecraftServer server, String worldKey, ManualConfig.SurvivalRecruitmentState st) {
        List<String> out = new ArrayList<>();
        boolean enabled = AIPlayer.CONFIG != null && AIPlayer.CONFIG.isSurvivalRecruitmentMode();
        out.add("Survival recruitment mode: " + enabled);
        out.add("World key: " + (worldKey == null ? "default" : worldKey));
        if (st == null) {
            out.add("State: <null>");
            return out;
        }

        out.add("Recruited: " + st.isRecruited() + " (botAlias=" + st.getBotAlias() + ")");
        if (st.isRecruited()) {
            String by = (st.getRecruitedByName() == null || st.getRecruitedByName().isBlank()) ? "unknown" : st.getRecruitedByName();
            out.add("Recruited by: " + by);
            out.add("Recruited at (epoch ms): " + st.getRecruitedAtEpochMs());
        }

        out.add("Companion quest: stage=" + st.getCompanionQuestStage() + " permanent=" + st.isPermanentCompanion());
        if (st.isCompanionAnchorSet()) {
            BlockPos anchor = BlockPos.fromLong(st.getCompanionAnchorPos());
            String dim = st.getCompanionAnchorDimension();
            out.add("Anchor: set=true dim=" + (dim == null ? "?" : dim) + " pos=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
        } else {
            out.add("Anchor: set=false");
        }

        // If enabled, hint about current gating state.
        if (server != null && enabled) {
            out.add("World recruited (gating): " + SurvivalRecruitmentService.isWorldRecruited(server));
        }
        return out;
    }
}
