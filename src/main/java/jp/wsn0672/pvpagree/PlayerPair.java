package jp.wsn0672.pvpagree;

import java.util.UUID;

public record PlayerPair(UUID first, UUID second) {
    public PlayerPair {
        if (first.compareTo(second) > 0) {
            UUID swap = first;
            first = second;
            second = swap;
        }
    }

    public static PlayerPair of(UUID first, UUID second) {
        return new PlayerPair(first, second);
    }

    public UUID other(UUID player) {
        return first.equals(player) ? second : first;
    }
}
