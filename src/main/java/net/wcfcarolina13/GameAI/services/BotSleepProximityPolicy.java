package net.wcfcarolina13.GameAI.services;

final class BotSleepProximityPolicy {

    private static final double COMMANDER_SLEEP_RADIUS = 16.0D;
    private static final double COMMANDER_SLEEP_RADIUS_SQ =
            COMMANDER_SLEEP_RADIUS * COMMANDER_SLEEP_RADIUS;

    private BotSleepProximityPolicy() {}

    static boolean isWithinCommanderSleepRadius(double squaredDistance) {
        return squaredDistance <= COMMANDER_SLEEP_RADIUS_SQ;
    }
}
