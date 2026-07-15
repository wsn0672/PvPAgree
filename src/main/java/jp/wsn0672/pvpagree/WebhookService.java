package jp.wsn0672.pvpagree;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;

public final class WebhookService {
    private final PvpAgreePlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookService(PvpAgreePlugin plugin) {
        this.plugin = plugin;
    }

    public void announce(String player1, String player2) {
        String content = plugin.getConfig().getString("discord-webhook.message", "{player1} vs {player2}")
                .replace("{player1}", player1).replace("{player2}", player2);
        send(content);
    }

    public void announceCombatLogout(String player, String opponent) {
        String content = plugin.getConfig().getString("combat-logout.webhook-message",
                        "⚠️ {player}さんが{opponent}さんとの戦闘中にログアウトしました！")
                .replace("{player}", player).replace("{opponent}", opponent);
        send(content);
    }

    private void send(String content) {
        if (!plugin.getConfig().getBoolean("discord-webhook.enabled")) {
            return;
        }
        String url = plugin.getConfig().getString("discord-webhook.url", "").trim();
        if (url.isEmpty()) {
            plugin.getLogger().warning("Discord Webhookが有効ですが、URLが設定されていません。");
            return;
        }
        String username = plugin.getConfig().getString("discord-webhook.username", "PvpAgree");
        String json = "{\"content\":\"" + escape(content) + "\",\"username\":\"" + escape(username) + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    plugin.getLogger().warning("Discord Webhookの送信に失敗しました。HTTP " + response.statusCode());
                }
            }).exceptionally(exception -> {
                plugin.getLogger().log(Level.WARNING, "Discord Webhookの送信に失敗しました。", exception);
                return null;
            });
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(Level.WARNING, "Discord Webhook URLが不正です。", exception);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
