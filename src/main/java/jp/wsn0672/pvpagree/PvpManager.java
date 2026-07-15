package jp.wsn0672.pvpagree;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PvpManager {
    private static final long ONE_MINUTE = 60_000L;
    public enum RequestResult { REQUEST_SENT, AGREEMENT_STARTED, FAILED }

    private final PvpAgreePlugin plugin;
    private final PreferenceStore preferences;
    private final MessageService messages;
    private final WebhookService webhook;
    private final Map<PlayerPair, PvpRequest> requests = new HashMap<>();
    private final Map<PlayerPair, PvpRequest> allowRequests = new HashMap<>();
    private final Map<PlayerPair, FightState> fights = new HashMap<>();
    private final Map<PlayerPair, Long> alwaysAllowedCombat = new HashMap<>();
    private final Map<UUID, Long> lastRequestAt = new HashMap<>();
    private final Map<UUID, Deque<Long>> requestHistory = new HashMap<>();

    public PvpManager(PvpAgreePlugin plugin, PreferenceStore preferences, MessageService messages,
                      WebhookService webhook) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.messages = messages;
        this.webhook = webhook;
    }

    public RequestResult request(Player sender, Player target) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            messages.send(sender, "cannot-request-self");
            return RequestResult.FAILED;
        }
        PlayerPair pair = PlayerPair.of(sender.getUniqueId(), target.getUniqueId());
        FightState staleFight = fights.get(pair);
        if (staleFight != null && staleFight.expired(now())) endFight(pair);
        if (canDamage(sender.getUniqueId(), target.getUniqueId())) {
            messages.send(sender, "already-active", Map.of("player", target.getName()));
            return RequestResult.FAILED;
        }
        PvpRequest existing = requests.get(pair);
        if (isValid(existing) && existing.sender().equals(sender.getUniqueId())) {
            messages.send(sender, "already-requested", Map.of("player", target.getName()));
            return RequestResult.FAILED;
        }
        // 相手から届いた申請に同じ/pvpやスニーク連打で応じた場合は、その操作を承諾として扱う。
        if (isValid(existing) && existing.sender().equals(target.getUniqueId())
                && existing.target().equals(sender.getUniqueId())) {
            startAgreement(sender, target);
            return RequestResult.AGREEMENT_STARTED;
        }
        if (!checkRequestLimit(sender)) return RequestResult.FAILED;
        if (preferences.isBlocked(target.getUniqueId(), sender.getUniqueId())) {
            messages.send(sender, "blocked-by-player", Map.of("player", target.getName()));
            return RequestResult.FAILED;
        }
        PlayerPreference preference = preferences.getPreference(target.getUniqueId());
        if (preference == PlayerPreference.ALWAYS_DENY) {
            messages.send(sender, "auto-denied", Map.of("player", target.getName()));
            return RequestResult.FAILED;
        }

        countRequest(sender.getUniqueId());
        if (preference == PlayerPreference.AUTO_ACCEPT) {
            startAgreement(sender, target);
            return RequestResult.AGREEMENT_STARTED;
        }
        long timeout = Math.max(1, plugin.getConfig().getLong("request-timeout-seconds", 30));
        requests.put(pair, new PvpRequest(sender.getUniqueId(), target.getUniqueId(), now() + timeout * 1000L));
        messages.send(sender, "request-sent", Map.of("player", target.getName()));
        sendRequestNotification(sender, target);
        return RequestResult.REQUEST_SENT;
    }

    private boolean checkRequestLimit(Player sender) {
        String bypass = plugin.getConfig().getString("request-limits.bypass-permission", "pvpagree.bypasslimits");
        if (!bypass.isBlank() && sender.hasPermission(bypass)) return true;
        long time = now();
        long cooldown = Math.max(0, plugin.getConfig().getLong("request-limits.cooldown-seconds", 5)) * 1000L;
        long remaining = lastRequestAt.getOrDefault(sender.getUniqueId(), 0L) + cooldown - time;
        if (remaining > 0) {
            messages.send(sender, "request-cooldown", Map.of("seconds", Long.toString((remaining + 999) / 1000)));
            return false;
        }
        int maxPending = Math.max(1, plugin.getConfig().getInt("request-limits.max-pending-sent", 3));
        long pending = requests.values().stream().filter(request -> request.sender().equals(sender.getUniqueId())
                && isValid(request)).count()
                + allowRequests.values().stream().filter(request -> request.sender().equals(sender.getUniqueId())
                && isValid(request)).count();
        if (pending >= maxPending) {
            messages.send(sender, "too-many-pending", Map.of("limit", Integer.toString(maxPending)));
            return false;
        }
        Deque<Long> history = requestHistory.computeIfAbsent(sender.getUniqueId(), ignored -> new ArrayDeque<>());
        history.removeIf(timestamp -> timestamp <= time - ONE_MINUTE);
        int maxPerMinute = Math.max(1, plugin.getConfig().getInt("request-limits.max-per-minute", 10));
        if (history.size() >= maxPerMinute) {
            messages.send(sender, "too-many-requests", Map.of("limit", Integer.toString(maxPerMinute)));
            return false;
        }
        return true;
    }

    private void countRequest(UUID sender) {
        long time = now();
        lastRequestAt.put(sender, time);
        requestHistory.computeIfAbsent(sender, ignored -> new ArrayDeque<>()).addLast(time);
    }

    private void sendRequestNotification(Player sender, Player target) {
        Map<String, String> placeholders = Map.of("player", sender.getName());
        target.sendMessage(messages.component("messages.request-header"));
        target.sendMessage(messages.component("messages.request-received", placeholders));
        Component accept = messages.component("messages.accept-button")
                .clickEvent(ClickEvent.runCommand("/pvp accept " + sender.getUniqueId()))
                .hoverEvent(HoverEvent.showText(messages.component("messages.accept-hover", placeholders)));
        Component deny = messages.component("messages.deny-button")
                .clickEvent(ClickEvent.runCommand("/pvp deny " + sender.getUniqueId()))
                .hoverEvent(HoverEvent.showText(messages.component("messages.deny-hover", placeholders)));
        target.sendMessage(messages.component("messages.request-actions-prefix").append(accept)
                .append(messages.component("messages.actions-space")).append(deny));
        target.sendMessage(messages.component("messages.request-open-gui"));
        target.sendMessage(messages.component("messages.request-footer"));
        playConfiguredSound(target, "sounds.request", Sound.ENTITY_ARROW_HIT_PLAYER);
    }

    public Optional<PvpRequest> latestIncoming(UUID target) {
        return requests.values().stream()
                .filter(request -> request.target().equals(target) && isValid(request))
                .max(Comparator.comparingLong(PvpRequest::expiresAt));
    }

    public boolean hasIncomingFrom(UUID target, UUID sender) {
        PvpRequest request = requests.get(PlayerPair.of(target, sender));
        return isValid(request) && request.target().equals(target) && request.sender().equals(sender);
    }

    public boolean hasOutgoingTo(UUID sender, UUID target) {
        PvpRequest request = requests.get(PlayerPair.of(sender, target));
        return isValid(request) && request.sender().equals(sender) && request.target().equals(target);
    }

    public Optional<PvpRequest> latestAllowIncoming(UUID target) {
        return allowRequests.values().stream()
                .filter(request -> request.target().equals(target) && isValid(request))
                .max(Comparator.comparingLong(PvpRequest::expiresAt));
    }

    public boolean hasOutgoingAllowRequest(UUID sender, UUID target) {
        PvpRequest request = allowRequests.get(PlayerPair.of(sender, target));
        return isValid(request) && request.sender().equals(sender) && request.target().equals(target);
    }

    public void requestAlwaysAllow(Player sender, Player target) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (senderId.equals(targetId)) {
            messages.send(sender, "cannot-allow-self");
            return;
        }
        if (preferences.isMutuallyAlwaysAllowed(senderId, targetId)) {
            messages.send(sender, "already-always-allowed", Map.of("player", target.getName()));
            return;
        }
        if (preferences.isBlocked(targetId, senderId)) {
            messages.send(sender, "blocked-by-player", Map.of("player", target.getName()));
            return;
        }
        PlayerPair pair = PlayerPair.of(senderId, targetId);
        PvpRequest existing = allowRequests.get(pair);
        if (isValid(existing) && existing.sender().equals(senderId)) {
            messages.send(sender, "allow-request-already-sent", Map.of("player", target.getName()));
            return;
        }
        if (isValid(existing) && existing.sender().equals(targetId) && existing.target().equals(senderId)) {
            establishAlwaysAllowed(sender, target);
            return;
        }
        if (!checkRequestLimit(sender)) return;
        countRequest(senderId);
        long timeout = Math.max(1, plugin.getConfig().getLong("allowlist-request-timeout-seconds", 30));
        allowRequests.put(pair, new PvpRequest(senderId, targetId, now() + timeout * 1000L));
        messages.send(sender, "allow-request-sent", Map.of("player", target.getName()));
        sendAllowRequestNotification(sender, target);
    }

    private void sendAllowRequestNotification(Player sender, Player target) {
        Map<String, String> values = Map.of("player", sender.getName());
        target.sendMessage(messages.component("messages.allow-request-header"));
        target.sendMessage(messages.component("messages.allow-request-received", values));
        Component accept = messages.component("messages.allow-accept-button")
                .clickEvent(ClickEvent.runCommand("/pvp allowaccept " + sender.getUniqueId()))
                .hoverEvent(HoverEvent.showText(messages.component("messages.allow-accept-hover", values)));
        Component deny = messages.component("messages.allow-deny-button")
                .clickEvent(ClickEvent.runCommand("/pvp allowdeny " + sender.getUniqueId()))
                .hoverEvent(HoverEvent.showText(messages.component("messages.allow-deny-hover", values)));
        target.sendMessage(messages.component("messages.request-actions-prefix").append(accept)
                .append(messages.component("messages.actions-space")).append(deny));
        target.sendMessage(messages.component("messages.request-open-gui"));
        target.sendMessage(messages.component("messages.allow-request-footer"));
        playConfiguredSound(target, "sounds.request", Sound.ENTITY_ARROW_HIT_PLAYER);
    }

    public void acceptAlwaysAllow(Player target, UUID senderId) {
        PvpRequest request = validAllowIncoming(target.getUniqueId(), senderId);
        Player sender = Bukkit.getPlayer(senderId);
        if (request == null || sender == null) {
            messages.send(target, "no-allow-request");
            return;
        }
        establishAlwaysAllowed(sender, target);
    }

    public void denyAlwaysAllow(Player target, UUID senderId) {
        PvpRequest request = validAllowIncoming(target.getUniqueId(), senderId);
        if (request == null) {
            messages.send(target, "no-allow-request");
            return;
        }
        allowRequests.remove(PlayerPair.of(senderId, target.getUniqueId()));
        messages.send(target, "allow-request-denied", Map.of("player", playerName(senderId)));
        Player sender = Bukkit.getPlayer(senderId);
        if (sender != null) messages.send(sender, "your-allow-request-denied", Map.of("player", target.getName()));
    }

    private PvpRequest validAllowIncoming(UUID target, UUID sender) {
        PvpRequest request = allowRequests.get(PlayerPair.of(sender, target));
        return isValid(request) && request.sender().equals(sender) && request.target().equals(target) ? request : null;
    }

    private void establishAlwaysAllowed(Player first, Player second) {
        PlayerPair pair = PlayerPair.of(first.getUniqueId(), second.getUniqueId());
        allowRequests.remove(pair);
        requests.remove(pair);
        clearFightSilently(pair);
        preferences.allowAlwaysMutual(first.getUniqueId(), second.getUniqueId());
        messages.send(first, "always-allowed-established", Map.of("player", second.getName()));
        messages.send(second, "always-allowed-established", Map.of("player", first.getName()));
        playConfiguredSound(first, "sounds.accepted", Sound.BLOCK_NOTE_BLOCK_PLING);
        playConfiguredSound(second, "sounds.accepted", Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    public void removeAlwaysAllowed(Player remover, UUID other) {
        if (!preferences.isMutuallyAlwaysAllowed(remover.getUniqueId(), other)) {
            messages.send(remover, "not-always-allowed", Map.of("player", playerName(other)));
            return;
        }
        PlayerPair pair = PlayerPair.of(remover.getUniqueId(), other);
        preferences.unallowAlwaysMutual(remover.getUniqueId(), other);
        alwaysAllowedCombat.remove(pair);
        clearFightSilently(pair);
        messages.send(remover, "always-allowed-removed", Map.of("player", playerName(other)));
        Player otherPlayer = Bukkit.getPlayer(other);
        if (otherPlayer != null) {
            messages.send(otherPlayer, "always-allowed-removed-by-player", Map.of("player", remover.getName()));
        }
    }

    public void accept(Player target, UUID senderId) {
        PvpRequest request = validIncoming(target.getUniqueId(), senderId);
        if (request == null) {
            messages.send(target, "no-request");
            return;
        }
        Player sender = Bukkit.getPlayer(senderId);
        requests.remove(PlayerPair.of(senderId, target.getUniqueId()));
        if (sender == null) {
            messages.send(target, "no-request");
            return;
        }
        startAgreement(sender, target);
    }

    private void startAgreement(Player player1, Player player2) {
        PlayerPair pair = PlayerPair.of(player1.getUniqueId(), player2.getUniqueId());
        requests.remove(pair);
        long agreementDuration = Math.max(1,
                plugin.getConfig().getLong("agreement-timeout-seconds", 20)) * 1000L;
        fights.put(pair, new FightState(now() + agreementDuration));
        String duration = Long.toString(Math.max(1, plugin.getConfig().getLong("combat-timeout-seconds", 20)));
        String readyDuration = Long.toString(agreementDuration / 1000L);
        messages.send(player1, "agreement-ready", Map.of("player", player2.getName(), "seconds", duration,
                "ready-seconds", readyDuration));
        messages.send(player2, "agreement-ready", Map.of("player", player1.getName(), "seconds", duration,
                "ready-seconds", readyDuration));
        playConfiguredSound(player1, "sounds.accepted", Sound.BLOCK_NOTE_BLOCK_PLING);
        playConfiguredSound(player2, "sounds.accepted", Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    public void deny(Player target, UUID senderId, boolean permanent) {
        PvpRequest request = validIncoming(target.getUniqueId(), senderId);
        if (request == null) {
            messages.send(target, "no-request");
            return;
        }
        requests.remove(PlayerPair.of(senderId, target.getUniqueId()));
        Player sender = Bukkit.getPlayer(senderId);
        String senderName = playerName(senderId);
        if (permanent) {
            preferences.block(target.getUniqueId(), senderId);
            messages.send(target, "permanently-denied", Map.of("player", senderName));
        } else {
            messages.send(target, "request-denied", Map.of("player", senderName));
        }
        if (sender != null) messages.send(sender, "your-request-denied", Map.of("player", target.getName()));
    }

    public void cancel(Player sender, String targetName) {
        Optional<PvpRequest> found = requests.values().stream()
                .filter(request -> request.sender().equals(sender.getUniqueId()) && isValid(request))
                .filter(request -> playerName(request.target()).equalsIgnoreCase(targetName))
                .findFirst();
        if (found.isEmpty()) {
            messages.send(sender, "no-sent-request", Map.of("player", targetName));
            return;
        }
        PvpRequest request = found.get();
        requests.remove(PlayerPair.of(request.sender(), request.target()));
        messages.send(sender, "request-cancelled", Map.of("player", playerName(request.target())));
        Player target = Bukkit.getPlayer(request.target());
        if (target != null) messages.send(target, "request-cancelled-by-sender", Map.of("player", sender.getName()));
    }

    public void cancelAll(Player sender) {
        List<PvpRequest> removed = requests.values().stream()
                .filter(request -> request.sender().equals(sender.getUniqueId())).toList();
        removed.forEach(request -> requests.remove(PlayerPair.of(request.sender(), request.target())));
        for (PvpRequest request : removed) {
            Player target = Bukkit.getPlayer(request.target());
            if (target != null) messages.send(target, "request-cancelled-by-sender", Map.of("player", sender.getName()));
        }
        messages.send(sender, removed.isEmpty() ? "no-sent-requests" : "all-requests-cancelled",
                Map.of("count", Integer.toString(removed.size())));
    }

    private PvpRequest validIncoming(UUID target, UUID sender) {
        PvpRequest request = requests.get(PlayerPair.of(sender, target));
        return isValid(request) && request.sender().equals(sender) && request.target().equals(target) ? request : null;
    }

    private boolean isValid(PvpRequest request) {
        return request != null && request.expiresAt() > now();
    }

    public boolean canDamage(UUID player1, UUID player2) {
        if (preferences.isMutuallyAlwaysAllowed(player1, player2)) return true;
        FightState fight = fights.get(PlayerPair.of(player1, player2));
        return fight != null && !fight.expired(now());
    }

    public void recordHit(UUID attacker, UUID victim) {
        PlayerPair pair = PlayerPair.of(attacker, victim);
        if (preferences.isMutuallyAlwaysAllowed(attacker, victim)) {
            long time = now();
            if (alwaysAllowedCombat.getOrDefault(pair, 0L) <= time) {
                announcePvpStart(playerName(attacker), playerName(victim));
            }
            alwaysAllowedCombat.put(pair, time + combatDurationMillis());
            return;
        }
        FightState fight = fights.get(pair);
        if (fight == null || fight.expired(now())) return;
        long durationMillis = combatDurationMillis();
        boolean firstHit = !fight.started();
        fight.expiresAt = now() + durationMillis;
        if (firstHit) {
            Player attackerPlayer = Bukkit.getPlayer(attacker);
            Player victimPlayer = Bukkit.getPlayer(victim);
            if (attackerPlayer != null) messages.send(attackerPlayer, "pvp-started", Map.of("player", playerName(victim)));
            if (victimPlayer != null) messages.send(victimPlayer, "pvp-started", Map.of("player", playerName(attacker)));
            announcePvpStart(playerName(attacker), playerName(victim));
            createBossBar(pair, fight);
        }
    }

    private void announcePvpStart(String player1, String player2) {
        webhook.announce(player1, player2);
        if (plugin.getConfig().getBoolean("minecraft-broadcast.enabled", true)) {
            Bukkit.broadcast(messages.component("minecraft-broadcast.message",
                    Map.of("player1", player1, "player2", player2)));
        }
    }

    public void endFightsFor(UUID player) {
        List<PlayerPair> pairs = fights.keySet().stream()
                .filter(pair -> pair.first().equals(player) || pair.second().equals(player)).toList();
        pairs.forEach(this::endFight);
    }

    public void handleQuit(Player player) {
        cancelRequestsForQuit(player);
        List<PlayerPair> unstarted = fights.entrySet().stream()
                .filter(entry -> !entry.getValue().started())
                .map(Map.Entry::getKey)
                .filter(pair -> pair.first().equals(player.getUniqueId()) || pair.second().equals(player.getUniqueId()))
                .toList();
        unstarted.forEach(this::endFight);
        for (Map.Entry<PlayerPair, FightState> entry : fights.entrySet()) {
            PlayerPair pair = entry.getKey();
            FightState fight = entry.getValue();
            if (!fight.started() || (!pair.first().equals(player.getUniqueId())
                    && !pair.second().equals(player.getUniqueId()))) continue;
            if (!fight.logoutHandled.add(player.getUniqueId())) continue;
            UUID otherId = pair.other(player.getUniqueId());
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) messages.send(other, "combat-logout", Map.of("player", player.getName()));
            if (plugin.getConfig().getBoolean("combat-logout.enabled", true)) {
                for (String command : plugin.getConfig().getStringList("combat-logout.commands")) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                            .replace("{player}", player.getName()).replace("{opponent}", playerName(otherId)));
                }
                if (plugin.getConfig().getBoolean("combat-logout.send-webhook", true)) {
                    webhook.announceCombatLogout(player.getName(), playerName(otherId));
                }
            }
        }
    }

    private void cancelRequestsForQuit(Player player) {
        UUID playerId = player.getUniqueId();
        List<PvpRequest> cancelledRequests = new ArrayList<>();
        requests.entrySet().removeIf(entry -> {
            PvpRequest request = entry.getValue();
            if (!request.sender().equals(playerId) && !request.target().equals(playerId)) return false;
            cancelledRequests.add(request);
            return true;
        });
        for (PvpRequest request : cancelledRequests) {
            UUID otherId = request.sender().equals(playerId) ? request.target() : request.sender();
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) {
                messages.send(other, "request-cancelled-disconnect", Map.of("player", player.getName()));
            }
        }

        List<PvpRequest> cancelledAllowRequests = new ArrayList<>();
        allowRequests.entrySet().removeIf(entry -> {
            PvpRequest request = entry.getValue();
            if (!request.sender().equals(playerId) && !request.target().equals(playerId)) return false;
            cancelledAllowRequests.add(request);
            return true;
        });
        for (PvpRequest request : cancelledAllowRequests) {
            UUID otherId = request.sender().equals(playerId) ? request.target() : request.sender();
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) {
                messages.send(other, "allow-request-cancelled-disconnect", Map.of("player", player.getName()));
            }
        }
    }

    public void handleJoin(Player player) {
        for (Map.Entry<PlayerPair, FightState> entry : fights.entrySet()) {
            if (entry.getValue().bossBar != null && (entry.getKey().first().equals(player.getUniqueId())
                    || entry.getKey().second().equals(player.getUniqueId()))) {
                player.showBossBar(entry.getValue().bossBar);
            }
        }
    }

    public void handleConfigReload() {
        for (Map.Entry<PlayerPair, FightState> entry : fights.entrySet()) {
            FightState fight = entry.getValue();
            if (fight.bossBar == null) continue;
            Player first = Bukkit.getPlayer(entry.getKey().first());
            Player second = Bukkit.getPlayer(entry.getKey().second());
            if (first != null) first.hideBossBar(fight.bossBar);
            if (second != null) second.hideBossBar(fight.bossBar);
            fight.bossBar = null;
        }
    }

    public void tick() {
        expireRequests();
        expireAllowRequests();
        long time = now();
        List<PlayerPair> expired = fights.entrySet().stream()
                .filter(entry -> entry.getValue().expired(time))
                .map(Map.Entry::getKey).toList();
        expired.forEach(this::endFight);

        for (Map.Entry<PlayerPair, FightState> entry : fights.entrySet()) {
            FightState fight = entry.getValue();
            if (!fight.started()) continue;
            long remainingMillis = Math.max(0, fight.expiresAt - time);
            long seconds = Math.max(1, (remainingMillis + 999) / 1000);
            updateDisplay(entry.getKey(), fight, seconds, remainingMillis);
        }
        requestHistory.values().forEach(history -> history.removeIf(timestamp -> timestamp <= time - ONE_MINUTE));
        alwaysAllowedCombat.entrySet().removeIf(entry -> entry.getValue() <= time);
    }

    private void expireAllowRequests() {
        long time = now();
        List<PvpRequest> expired = new ArrayList<>();
        allowRequests.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() > time) return false;
            expired.add(entry.getValue());
            return true;
        });
        for (PvpRequest request : expired) {
            Player sender = Bukkit.getPlayer(request.sender());
            Player target = Bukkit.getPlayer(request.target());
            if (sender != null) messages.send(sender, "allow-request-expired-sender",
                    Map.of("player", playerName(request.target())));
            if (target != null) messages.send(target, "allow-request-expired-target",
                    Map.of("player", playerName(request.sender())));
        }
    }

    private void expireRequests() {
        long time = now();
        List<PvpRequest> expired = new ArrayList<>();
        Iterator<Map.Entry<PlayerPair, PvpRequest>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            PvpRequest request = iterator.next().getValue();
            if (request.expiresAt() <= time) {
                expired.add(request);
                iterator.remove();
            }
        }
        for (PvpRequest request : expired) {
            Player sender = Bukkit.getPlayer(request.sender());
            Player target = Bukkit.getPlayer(request.target());
            if (sender != null) messages.send(sender, "request-expired-sender", Map.of("player", playerName(request.target())));
            if (target != null) messages.send(target, "request-expired-target", Map.of("player", playerName(request.sender())));
        }
    }

    private void updateDisplay(PlayerPair pair, FightState fight, long seconds, long remainingMillis) {
        String mode = plugin.getConfig().getString("combat-display.mode", "ACTION_BAR").toUpperCase(Locale.ROOT);
        Map<String, String> firstValues = Map.of("player", playerName(pair.second()), "seconds", Long.toString(seconds));
        Map<String, String> secondValues = Map.of("player", playerName(pair.first()), "seconds", Long.toString(seconds));
        Player first = Bukkit.getPlayer(pair.first());
        Player second = Bukkit.getPlayer(pair.second());
        if (mode.equals("ACTION_BAR")) {
            if (first != null) first.sendActionBar(messages.component("combat-display.action-bar", firstValues));
            if (second != null) second.sendActionBar(messages.component("combat-display.action-bar", secondValues));
        } else if (mode.equals("BOSS_BAR")) {
            if (fight.bossBar == null) createBossBar(pair, fight);
            if (fight.bossBar != null) {
                fight.bossBar.name(messages.component("combat-display.boss-bar", Map.of("seconds", Long.toString(seconds))));
                float progress = (float) Math.max(0.0, Math.min(1.0, remainingMillis / (double) combatDurationMillis()));
                fight.bossBar.progress(progress);
            }
        }
    }

    private void createBossBar(PlayerPair pair, FightState fight) {
        if (!plugin.getConfig().getString("combat-display.mode", "ACTION_BAR").equalsIgnoreCase("BOSS_BAR")) return;
        BossBar.Color color;
        BossBar.Overlay overlay;
        try {
            color = BossBar.Color.valueOf(plugin.getConfig().getString("combat-display.boss-bar-color", "RED")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            color = BossBar.Color.RED;
        }
        try {
            overlay = BossBar.Overlay.valueOf(plugin.getConfig().getString("combat-display.boss-bar-overlay", "PROGRESS")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            overlay = BossBar.Overlay.PROGRESS;
        }
        fight.bossBar = BossBar.bossBar(Component.empty(), 1.0f, color, overlay);
        Player first = Bukkit.getPlayer(pair.first());
        Player second = Bukkit.getPlayer(pair.second());
        if (first != null) first.showBossBar(fight.bossBar);
        if (second != null) second.showBossBar(fight.bossBar);
    }

    private void endFight(PlayerPair pair) {
        FightState fight = fights.remove(pair);
        if (fight == null) return;
        Player first = Bukkit.getPlayer(pair.first());
        Player second = Bukkit.getPlayer(pair.second());
        if (fight.bossBar != null) {
            if (first != null) first.hideBossBar(fight.bossBar);
            if (second != null) second.hideBossBar(fight.bossBar);
        }
        String key = fight.started() ? "pvp-ended" : "agreement-expired";
        if (first != null) messages.send(first, key, Map.of("player", playerName(pair.second())));
        if (second != null) messages.send(second, key, Map.of("player", playerName(pair.first())));
    }

    private void clearFightSilently(PlayerPair pair) {
        FightState fight = fights.remove(pair);
        if (fight == null || fight.bossBar == null) return;
        Player first = Bukkit.getPlayer(pair.first());
        Player second = Bukkit.getPlayer(pair.second());
        if (first != null) first.hideBossBar(fight.bossBar);
        if (second != null) second.hideBossBar(fight.bossBar);
    }

    private void playConfiguredSound(Player player, String path, Sound fallback) {
        if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;
        String raw = plugin.getConfig().getString(path + ".sound", fallback.key().asString());
        float volume = (float) plugin.getConfig().getDouble(path + ".volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 1.0);
        player.playSound(player.getLocation(), raw, volume, pitch);
    }

    private long combatDurationMillis() {
        return Math.max(1, plugin.getConfig().getLong("combat-timeout-seconds", 20)) * 1000L;
    }

    private String playerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private static final class FightState {
        private final long agreementExpiresAt;
        private long expiresAt;
        private BossBar bossBar;
        private final Set<UUID> logoutHandled = new HashSet<>();

        private FightState(long agreementExpiresAt) {
            this.agreementExpiresAt = agreementExpiresAt;
        }

        private boolean started() {
            return expiresAt > 0;
        }

        private boolean expired(long time) {
            return started() ? expiresAt <= time : agreementExpiresAt <= time;
        }
    }
}
