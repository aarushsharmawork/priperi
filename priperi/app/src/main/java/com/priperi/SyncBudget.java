package com.priperi;

import java.util.ArrayDeque;

/** Main-thread rolling budget; server quotas remain the authority. */
final class SyncBudget {
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    long delay(long now) {
        while (!starts.isEmpty() && now - starts.peekFirst() >= 60_000) starts.removeFirst();
        return starts.size() < 24 ? 0 : Math.max(0, starts.peekFirst() + 60_000 - now);
    }
    void record(long now) {
        if (delay(now) != 0) throw new IllegalStateException("Sync budget exhausted");
        starts.addLast(now);
    }
}
