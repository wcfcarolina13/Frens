package net.wcfcarolina13.GameAI.services;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One pending ritual per server and alias; connection must succeed before its offering commits. */
public final class RitualAttemptPolicy {
    private final Map<Object, Map<String, Attempt>> pending = new IdentityHashMap<>();

    public static final class Attempt {
        private final Object server;
        private final String alias;
        private boolean started;

        private Attempt(Object server, String alias) {
            this.server = server;
            this.alias = alias;
        }
    }

    public synchronized Attempt claim(Object server, String alias) {
        if (server == null || alias == null || alias.isBlank()) return null;
        String key = alias.trim().toLowerCase(Locale.ROOT);
        Map<String, Attempt> byAlias = pending.computeIfAbsent(server, ignored -> new HashMap<>());
        if (byAlias.containsKey(key)) return null;
        Attempt attempt = new Attempt(server, key);
        byAlias.put(key, attempt);
        return attempt;
    }

    public synchronized boolean isCurrent(Attempt attempt) {
        if (attempt == null) return false;
        Map<String, Attempt> byAlias = pending.get(attempt.server);
        return byAlias != null && byAlias.get(attempt.alias) == attempt;
    }

    public synchronized void release(Attempt attempt) {
        if (attempt == null) return;
        Map<String, Attempt> byAlias = pending.get(attempt.server);
        if (byAlias != null && byAlias.remove(attempt.alias, attempt) && byAlias.isEmpty()) {
            pending.remove(attempt.server);
        }
    }

    public void execute(Attempt attempt, BooleanSupplier admitted, Runnable connect, Runnable commit) {
        synchronized (this) {
            if (!isCurrent(attempt) || attempt.started) {
                throw new CancellationException("This ritual attempt is no longer pending");
            }
            attempt.started = true;
        }
        try {
            if (!admitted.getAsBoolean()) throw new CancellationException("Ritual conditions changed");
            connect.run();
            if (!isCurrent(attempt)) throw new CancellationException("Ritual attempt was replaced");
            commit.run();
        } finally {
            release(attempt);
        }
    }
}
