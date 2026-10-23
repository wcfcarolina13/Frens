package net.wcfcarolina13.GameAI.services;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-bot respawn completion and lifecycle identity for queued fallback checks. */
public final class RespawnHandlingTracker {
    private static final class Entry {
        private long handledTick = -1;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    public synchronized Object captureLifecycle(UUID botUuid) {
        return botUuid == null ? null : entries.computeIfAbsent(botUuid, ignored -> new Entry());
    }

    public synchronized boolean isCurrent(UUID botUuid, Object lifecycle) {
        return lifecycle != null && entries.get(botUuid) == lifecycle;
    }

    public synchronized void recordHandled(UUID botUuid, long tick) {
        if (botUuid != null) {
            entries.computeIfAbsent(botUuid, ignored -> new Entry()).handledTick = tick;
        }
    }

    public synchronized long lastHandledTick(UUID botUuid) {
        Entry entry = entries.get(botUuid);
        return entry == null ? -1 : entry.handledTick;
    }

    /** Retains the existing comparison against the fallback's captured check tick. */
    public synchronized boolean handledWithin(UUID botUuid, long checkTick, long window) {
        long last = lastHandledTick(botUuid);
        return last >= 0 && checkTick - last <= window;
    }

    public synchronized void forget(UUID botUuid) {
        entries.remove(botUuid);
    }

    public synchronized void reset() {
        entries.clear();
    }
}
