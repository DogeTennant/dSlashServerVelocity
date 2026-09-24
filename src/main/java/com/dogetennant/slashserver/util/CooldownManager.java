package com.dogetennant.slashserver.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last time each player used a slash-server command.
 *
 * Entries are only ever written on a successful switch, so a player who is
 * denied (no permission, already connected, server down) is never put on
 * cooldown for it.
 */
public class CooldownManager {

    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    /**
     * Seconds the player still has to wait, rounded up.
     *
     * @param cooldownSeconds configured cooldown; 0 or less disables it
     * @return 0 when the player may switch right now
     */
    public long remaining(UUID player, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0;
        }

        Long last = lastUse.get(player);
        if (last == null) {
            return 0;
        }

        long elapsedMillis = System.currentTimeMillis() - last;
        long remainingMillis = (cooldownSeconds * 1000L) - elapsedMillis;

        return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
    }

    public void markUsed(UUID player) {
        lastUse.put(player, System.currentTimeMillis());
    }

    public void clear(UUID player) {
        lastUse.remove(player);
    }

    public void clearAll() {
        lastUse.clear();
    }
}
