package jp.wsn0672.pvpagree;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PvpListener implements Listener {
    private final PvpAgreePlugin plugin;
    private final PvpManager manager;
    private final MenuService menus;
    private final MessageService messages;
    private final PreferenceStore preferences;
    private final Map<PlayerPair, Long> warningCooldown = new HashMap<>();
    private final Map<DirectedPair, SwingProgress> swings = new HashMap<>();
    private final Map<UUID, HeadMotion> headMotions = new HashMap<>();

    public PvpListener(PvpAgreePlugin plugin, PvpManager manager, MenuService menus, MessageService messages,
                       PreferenceStore preferences) {
        this.plugin = plugin;
        this.manager = manager;
        this.menus = menus;
        this.messages = messages;
        this.preferences = preferences;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        boolean wasAllowed = manager.canDamage(attacker.getUniqueId(), victim.getUniqueId());
        // 5回目の操作で承諾が成立しても、その操作自体ではダメージを与えない。
        if (!wasAllowed) {
            event.setCancelled(true);
            PlayerPair pair = PlayerPair.of(attacker.getUniqueId(), victim.getUniqueId());
            long now = System.currentTimeMillis();
            if (warningCooldown.getOrDefault(pair, 0L) <= now) {
                messages.send(attacker, "pvp-not-allowed", Map.of("player", victim.getName()));
                warningCooldown.put(pair, now + 1000L);
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (manager.canDamage(attacker.getUniqueId(), victim.getUniqueId())) {
            manager.recordHit(attacker.getUniqueId(), victim.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player actor = event.getPlayer();
        if (!plugin.getConfig().getBoolean("sneak-gesture.enabled", true)
                || !actor.isSneaking() || !preferences.isGestureEnabled(actor.getUniqueId())) return;
        if (plugin.getConfig().getBoolean("sneak-gesture.require-empty-main-hand", true)
                && !actor.getInventory().getItemInMainHand().getType().isAir()) return;
        Player target = lookedAtPlayer(actor);
        if (target == null || !preferences.isGestureEnabled(target.getUniqueId())) return;
        DirectedPair key = new DirectedPair(actor.getUniqueId(), target.getUniqueId());
        if (manager.hasOutgoingTo(actor.getUniqueId(), target.getUniqueId())
                || manager.canDamage(actor.getUniqueId(), target.getUniqueId())) {
            swings.remove(key);
            return;
        }
        swings.entrySet().removeIf(entry -> entry.getKey().actor().equals(actor.getUniqueId())
                && !entry.getKey().target().equals(target.getUniqueId()));
        int required = Math.max(1, plugin.getConfig().getInt("sneak-gesture.required-swings", 5));
        long window = Math.max(1, plugin.getConfig().getLong("sneak-gesture.window-seconds", 4)) * 1000L;
        long now = System.currentTimeMillis();
        SwingProgress progress = swings.computeIfAbsent(key, ignored -> new SwingProgress());
        if (now - progress.lastSwingAt > window) progress.swings = 0;
        progress.lastSwingAt = now;
        progress.swings++;
        boolean accepting = manager.hasIncomingFrom(actor.getUniqueId(), target.getUniqueId());
        if (progress.swings >= 2 && progress.swings < required) {
            actor.sendActionBar(messages.component(accepting ? "sneak-gesture.accept-progress"
                            : "sneak-gesture.request-progress",
                    Map.of("remaining", Integer.toString(required - progress.swings), "player", target.getName())));
        }
        if (progress.swings < required) return;
        swings.remove(key);
        if (accepting) {
            manager.accept(actor, target.getUniqueId());
        } else {
            PvpManager.RequestResult result = manager.request(actor, target);
            if (result == PvpManager.RequestResult.REQUEST_SENT) {
                actor.sendActionBar(messages.component("sneak-gesture.request-sent-action-bar",
                        Map.of("player", target.getName())));
            }
        }
    }

    private Player lookedAtPlayer(Player actor) {
        double maxDistance = Math.max(1.0, plugin.getConfig().getDouble("sneak-gesture.max-distance", 128.0));
        double raySize = Math.max(0.0, plugin.getConfig().getDouble("sneak-gesture.ray-size", 0.35));
        Location eye = actor.getEyeLocation();
        RayTraceResult result = actor.getWorld().rayTrace(eye, eye.getDirection(), maxDistance,
                FluidCollisionMode.NEVER, true, raySize,
                entity -> entity instanceof Player player && !player.equals(actor)
                        && player.getGameMode() != GameMode.SPECTATOR && !player.isDead());
        return result != null && result.getHitEntity() instanceof Player player ? player : null;
    }

    public void tickHeadGestures() {
        if (!plugin.getConfig().getBoolean("head-gesture.enabled", true)) {
            headMotions.clear();
            return;
        }
        long now = System.currentTimeMillis();
        boolean requireSneaking = plugin.getConfig().getBoolean("head-gesture.require-sneaking", true);
        double minimumStep = Math.max(0.1, plugin.getConfig().getDouble("head-gesture.minimum-step-degrees", 1.5));
        double dominance = Math.max(1.0, plugin.getConfig().getDouble("head-gesture.axis-dominance", 1.35));
        double amplitude = Math.max(1.0, plugin.getConfig().getDouble("head-gesture.minimum-amplitude-degrees", 12.0));
        int required = Math.max(2, plugin.getConfig().getInt("head-gesture.required-reversals", 4));
        long window = Math.max(1, plugin.getConfig().getLong("head-gesture.window-seconds", 4)) * 1000L;

        Set<UUID> eligible = new java.util.HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Optional<PvpRequest> incoming = manager.latestIncoming(player.getUniqueId());
            if (incoming.isEmpty() || !preferences.isGestureEnabled(player.getUniqueId())
                    || (requireSneaking && !player.isSneaking())) continue;
            eligible.add(player.getUniqueId());
            UUID sender = incoming.get().sender();
            HeadMotion motion = headMotions.get(player.getUniqueId());
            if (motion == null || !motion.sender.equals(sender)) {
                headMotions.put(player.getUniqueId(), new HeadMotion(sender, player.getYaw(), player.getPitch(), now));
                continue;
            }
            if (motion.sequenceStartedAt > 0 && now - motion.sequenceStartedAt > window) motion.resetAxes();
            double yawDelta = wrappedDegrees(player.getYaw() - motion.lastYaw);
            double pitchDelta = player.getPitch() - motion.lastPitch;
            motion.lastYaw = player.getYaw();
            motion.lastPitch = player.getPitch();

            double absYaw = Math.abs(yawDelta);
            double absPitch = Math.abs(pitchDelta);
            if (absPitch >= minimumStep && absPitch >= absYaw * dominance) {
                motion.horizontal.reset();
                if (motion.sequenceStartedAt == 0) motion.sequenceStartedAt = now;
                if (motion.vertical.add(pitchDelta, amplitude)) {
                    showHeadProgress(player, sender, true, motion.vertical.reversals, required);
                    if (motion.vertical.reversals >= required) {
                        headMotions.remove(player.getUniqueId());
                        manager.accept(player, sender);
                    }
                }
            } else if (absYaw >= minimumStep && absYaw >= absPitch * dominance) {
                motion.vertical.reset();
                if (motion.sequenceStartedAt == 0) motion.sequenceStartedAt = now;
                if (motion.horizontal.add(yawDelta, amplitude)) {
                    showHeadProgress(player, sender, false, motion.horizontal.reversals, required);
                    if (motion.horizontal.reversals >= required) {
                        headMotions.remove(player.getUniqueId());
                        manager.deny(player, sender, false);
                    }
                }
            }
        }
        headMotions.keySet().removeIf(uuid -> !eligible.contains(uuid));
    }

    private void showHeadProgress(Player player, UUID sender, boolean accepting, int count, int required) {
        if (count >= required) return;
        player.sendActionBar(messages.component(accepting ? "head-gesture.accept-progress"
                        : "head-gesture.deny-progress",
                Map.of("remaining", Integer.toString(required - count), "player", playerName(sender))));
    }

    private String playerName(UUID uuid) {
        String name = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    private double wrappedDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees > 180.0) degrees -= 360.0;
        if (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing instanceof Player player) return player;
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PvpMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        switch (holder.type()) {
            case RESPONSE -> menus.clickResponse(player, holder.sender(), slot);
            case ALLOW_RESPONSE -> menus.clickAllowResponse(player, holder.sender(), slot);
            case SETTINGS -> menus.clickSetting(player, slot);
            case BLOCKLIST -> menus.clickBlocklist(player, holder, slot);
            case ALLOWLIST -> menus.clickAllowlist(player, holder, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PvpMenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.getConfig().getBoolean("fight-end.end-on-death", true)) {
            manager.endFightsFor(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (plugin.getConfig().getBoolean("fight-end.end-on-world-change", true)) {
            manager.endFightsFor(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (plugin.getConfig().getBoolean("fight-end.end-on-teleport", false)) {
            manager.endFightsFor(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
        clearTransient(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    private void clearTransient(UUID player) {
        warningCooldown.entrySet().removeIf(entry -> entry.getKey().first().equals(player)
                || entry.getKey().second().equals(player));
        swings.entrySet().removeIf(entry -> entry.getKey().actor().equals(player)
                || entry.getKey().target().equals(player));
        headMotions.remove(player);
    }

    private record DirectedPair(UUID actor, UUID target) { }

    private static final class SwingProgress {
        private int swings;
        private long lastSwingAt;
    }

    private static final class HeadMotion {
        private final UUID sender;
        private float lastYaw;
        private float lastPitch;
        private long sequenceStartedAt;
        private final AxisMotion vertical = new AxisMotion();
        private final AxisMotion horizontal = new AxisMotion();

        private HeadMotion(UUID sender, float yaw, float pitch, long now) {
            this.sender = sender;
            this.lastYaw = yaw;
            this.lastPitch = pitch;
        }

        private void resetAxes() {
            vertical.reset();
            horizontal.reset();
            sequenceStartedAt = 0;
        }
    }

    private static final class AxisMotion {
        private int direction;
        private double travel;
        private int reversals;

        private boolean add(double delta, double requiredTravel) {
            int nextDirection = delta > 0 ? 1 : -1;
            if (direction == 0) {
                direction = nextDirection;
                travel = Math.abs(delta);
                return false;
            }
            if (direction == nextDirection) {
                travel += Math.abs(delta);
                return false;
            }
            boolean completed = travel >= requiredTravel;
            if (completed) reversals++;
            direction = nextDirection;
            travel = Math.abs(delta);
            return completed;
        }

        private void reset() {
            direction = 0;
            travel = 0;
            reversals = 0;
        }
    }
}
