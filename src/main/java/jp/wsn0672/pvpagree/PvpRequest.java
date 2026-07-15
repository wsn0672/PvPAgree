package jp.wsn0672.pvpagree;

import java.util.UUID;

public record PvpRequest(UUID sender, UUID target, long expiresAt) {
}
