package net.vg.codex.network;

/** A bounded monotonic-time window: paused singleplayer must not freeze the request budget. */
public final class QuestRequestBudget {
    private static final long WINDOW_NANOS = 50_000_000L;
    private long started;
    private int used;
    public boolean acquire(long now) {
        if (used == 0 || now - started >= WINDOW_NANOS) { started = now; used = 0; }
        return ++used <= 12;
    }
}
