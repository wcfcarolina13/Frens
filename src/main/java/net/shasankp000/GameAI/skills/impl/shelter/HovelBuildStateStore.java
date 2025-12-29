package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.GameAI.skills.SkillContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Small wrapper around SkillContext.sharedState() for persisting/resuming hovel build state.
 *
 * <p>This is intentionally minimal (string keys + simple value types) so it remains stable
 * across version bumps and can survive partial builds.</p>
 */
final class HovelBuildStateStore {

    private Map<String, Object> sharedState;
    private UUID botUuid;
    private String prefix;

    void attach(SkillContext context, ServerPlayerEntity bot) {
        this.sharedState = context != null ? context.sharedState() : null;
        this.botUuid = bot != null ? bot.getUuid() : null;
        if (this.sharedState != null && this.botUuid != null) {
            this.prefix = "shelter.hovel2." + this.botUuid + ".";
        } else {
            this.prefix = null;
        }
    }

    boolean isAttached() {
        return sharedState != null && prefix != null;
    }

    Map<String, Object> sharedState() {
        return sharedState;
    }

    String key(String suffix) {
        if (prefix == null || suffix == null) return null;
        return prefix + suffix;
    }

    void clearPersistedBuildState() {
        if (!isAttached()) return;

        // Clear only our own namespace.
        // We store a small fixed set of keys; removing by name keeps it predictable.
        sharedState.remove(key("build.center.x"));
        sharedState.remove(key("build.center.y"));
        sharedState.remove(key("build.center.z"));
        sharedState.remove(key("build.radius"));
        sharedState.remove(key("build.wallHeight"));
        sharedState.remove(key("build.doorSide"));
        sharedState.remove(key("plan.center.x"));
        sharedState.remove(key("plan.center.y"));
        sharedState.remove(key("plan.center.z"));
        sharedState.remove(key("plan.doorSide"));
        sharedState.remove(key("scaffold.usedBasesXZ"));
        sharedState.remove(key("roof.pendingPillars"));
    }

    void persistPlanAnchor(BlockPos requestedStandCenter, Direction doorSide) {
        if (!isAttached()) return;
        if (requestedStandCenter == null || doorSide == null) return;
        SharedStateUtils.setValue(sharedState, key("plan.center.x"), requestedStandCenter.getX());
        SharedStateUtils.setValue(sharedState, key("plan.center.y"), requestedStandCenter.getY());
        SharedStateUtils.setValue(sharedState, key("plan.center.z"), requestedStandCenter.getZ());
        SharedStateUtils.setValue(sharedState, key("plan.doorSide"), doorSide.asString());
    }

    void persistBuildSignature(BlockPos buildCenter, int radius, int wallHeight, Direction doorSide) {
        if (!isAttached()) return;
        if (buildCenter == null || doorSide == null) return;
        SharedStateUtils.setValue(sharedState, key("build.center.x"), buildCenter.getX());
        SharedStateUtils.setValue(sharedState, key("build.center.y"), buildCenter.getY());
        SharedStateUtils.setValue(sharedState, key("build.center.z"), buildCenter.getZ());
        SharedStateUtils.setValue(sharedState, key("build.radius"), radius);
        SharedStateUtils.setValue(sharedState, key("build.wallHeight"), wallHeight);
        SharedStateUtils.setValue(sharedState, key("build.doorSide"), doorSide.asString());
    }

    boolean restoreBuildStateIfCompatible(Logger logger,
                                         BlockPos buildCenter,
                                         int radius,
                                         int wallHeight,
                                         Direction doorSide,
                                         Set<BlockPos> usedScaffoldBasesXZ,
                                         Set<RoofPillar> pendingRoofPillars) {
        if (!isAttached() || buildCenter == null || doorSide == null) return false;

        Object cx = SharedStateUtils.getValue(sharedState, key("build.center.x"));
        Object cy = SharedStateUtils.getValue(sharedState, key("build.center.y"));
        Object cz = SharedStateUtils.getValue(sharedState, key("build.center.z"));
        Object r = SharedStateUtils.getValue(sharedState, key("build.radius"));
        Object h = SharedStateUtils.getValue(sharedState, key("build.wallHeight"));
        Object ds = SharedStateUtils.getValue(sharedState, key("build.doorSide"));

        if (!(cx instanceof Number) || !(cy instanceof Number) || !(cz instanceof Number)
                || !(r instanceof Number) || !(h instanceof Number) || ds == null) {
            return false;
        }

        BlockPos storedCenter = new BlockPos(((Number) cx).intValue(), ((Number) cy).intValue(), ((Number) cz).intValue());
        int storedRadius = ((Number) r).intValue();
        int storedHeight = ((Number) h).intValue();
        String storedDoorSide = ds.toString();

        boolean same = storedCenter.equals(buildCenter)
                && storedRadius == radius
                && storedHeight == wallHeight
                && storedDoorSide.equalsIgnoreCase(doorSide.asString());
        if (!same) {
            return false;
        }

        if (usedScaffoldBasesXZ != null) {
            usedScaffoldBasesXZ.clear();
            Object basesObj = SharedStateUtils.getValue(sharedState, key("scaffold.usedBasesXZ"));
            if (basesObj instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry == null) continue;
                    String s = entry.toString();
                    String[] parts = s.split(",");
                    if (parts.length != 2) continue;
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int z = Integer.parseInt(parts[1].trim());
                        usedScaffoldBasesXZ.add(new BlockPos(x, 0, z));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        if (pendingRoofPillars != null) {
            pendingRoofPillars.clear();
            Object pillarsObj = SharedStateUtils.getValue(sharedState, key("roof.pendingPillars"));
            if (pillarsObj instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry == null) continue;
                    String s = entry.toString();
                    String[] parts = s.split(",");
                    if (parts.length != 4) continue;
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int z = Integer.parseInt(parts[2].trim());
                        int topY = Integer.parseInt(parts[3].trim());
                        pendingRoofPillars.add(new RoofPillar(new BlockPos(x, y, z), topY));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        if (logger != null && usedScaffoldBasesXZ != null && pendingRoofPillars != null) {
            logger.info("Hovel: restored scaffold memory from sharedState (bases={}, pendingRoofPillars={})",
                    usedScaffoldBasesXZ.size(), pendingRoofPillars.size());
        }
        return true;
    }

    void persistUsedScaffoldBases(Set<BlockPos> usedScaffoldBasesXZ) {
        if (!isAttached() || usedScaffoldBasesXZ == null) return;
        ArrayList<String> out = new ArrayList<>(usedScaffoldBasesXZ.size());
        for (BlockPos p : usedScaffoldBasesXZ) {
            if (p == null) continue;
            out.add(p.getX() + "," + p.getZ());
        }
        SharedStateUtils.setValue(sharedState, key("scaffold.usedBasesXZ"), out);
    }

    void persistPendingRoofPillars(Set<RoofPillar> pendingRoofPillars) {
        if (!isAttached() || pendingRoofPillars == null) return;
        ArrayList<String> out = new ArrayList<>(pendingRoofPillars.size());
        for (RoofPillar pillar : pendingRoofPillars) {
            if (pillar == null || pillar.base() == null) continue;
            BlockPos b = pillar.base();
            out.add(b.getX() + "," + b.getY() + "," + b.getZ() + "," + pillar.topY());
        }
        SharedStateUtils.setValue(sharedState, key("roof.pendingPillars"), out);
    }
}
