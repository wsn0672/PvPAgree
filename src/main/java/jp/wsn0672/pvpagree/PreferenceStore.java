package jp.wsn0672.pvpagree;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PreferenceStore {
    private final PvpAgreePlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final ExecutorService writer;
    private BukkitTask pendingSave;

    public PreferenceStore(PvpAgreePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PvpAgree-DataWriter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public PlayerPreference getPreference(UUID player) {
        String raw = data.getString("players." + player + ".preference", PlayerPreference.SHOW.name());
        try {
            return PlayerPreference.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return PlayerPreference.SHOW;
        }
    }

    public void setPreference(UUID player, PlayerPreference preference) {
        data.set("players." + player + ".preference", preference.name());
        scheduleSave();
    }

    public boolean isGestureEnabled(UUID player) {
        return data.getBoolean("players." + player + ".sneak-gesture", true);
    }

    public void setGestureEnabled(UUID player, boolean enabled) {
        data.set("players." + player + ".sneak-gesture", enabled);
        scheduleSave();
    }

    public boolean isBlocked(UUID owner, UUID blocked) {
        return data.getStringList("players." + owner + ".blocked").contains(blocked.toString());
    }

    public boolean isAlwaysAllowed(UUID owner, UUID allowed) {
        return data.getStringList("players." + owner + ".always-allowed").contains(allowed.toString());
    }

    public boolean isMutuallyAlwaysAllowed(UUID first, UUID second) {
        return isAlwaysAllowed(first, second) && isAlwaysAllowed(second, first);
    }

    public List<UUID> getAlwaysAllowed(UUID owner) {
        return parseUuidList("players." + owner + ".always-allowed");
    }

    public List<UUID> getMutuallyAlwaysAllowed(UUID owner) {
        return getAlwaysAllowed(owner).stream()
                .filter(other -> isAlwaysAllowed(other, owner)).toList();
    }

    public List<UUID> getBlocked(UUID owner) {
        return parseUuidList("players." + owner + ".blocked");
    }

    private List<UUID> parseUuidList(String path) {
        return data.getStringList(path).stream().map(raw -> {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    public void block(UUID owner, UUID blocked) {
        Set<String> blockedPlayers = new HashSet<>(data.getStringList("players." + owner + ".blocked"));
        blockedPlayers.add(blocked.toString());
        data.set("players." + owner + ".blocked", blockedPlayers.stream().sorted().toList());
        removeFromList("players." + owner + ".always-allowed", blocked);
        removeFromList("players." + blocked + ".always-allowed", owner);
        scheduleSave();
    }

    public void allowAlwaysMutual(UUID first, UUID second) {
        addToList("players." + first + ".always-allowed", second);
        addToList("players." + second + ".always-allowed", first);
        removeFromList("players." + first + ".blocked", second);
        removeFromList("players." + second + ".blocked", first);
        scheduleSave();
    }

    public void unallowAlwaysMutual(UUID first, UUID second) {
        removeFromList("players." + first + ".always-allowed", second);
        removeFromList("players." + second + ".always-allowed", first);
        scheduleSave();
    }

    private void addToList(String path, UUID player) {
        Set<String> values = new HashSet<>(data.getStringList(path));
        values.add(player.toString());
        data.set(path, values.stream().sorted().toList());
    }

    public void unblock(UUID owner, UUID blocked) {
        removeFromList("players." + owner + ".blocked", blocked);
        scheduleSave();
    }

    private void removeFromList(String path, UUID player) {
        List<String> values = new java.util.ArrayList<>(data.getStringList(path));
        values.remove(player.toString());
        data.set(path, values);
    }

    private void scheduleSave() {
        if (pendingSave != null) pendingSave.cancel();
        long delay = Math.max(1, plugin.getConfig().getLong("data-save-delay-ticks", 40));
        pendingSave = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingSave = null;
            enqueueSnapshot(data.saveToString());
        }, delay);
    }

    private Future<?> enqueueSnapshot(String snapshot) {
        return writer.submit(() -> {
            try {
                Files.createDirectories(file.toPath().getParent());
                File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(temporary.toPath(), snapshot, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "data.ymlを保存できませんでした。", exception);
            }
        });
    }

    public void close() {
        if (pendingSave != null) pendingSave.cancel();
        try {
            enqueueSnapshot(data.saveToString()).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "終了時にdata.ymlを保存できませんでした。", exception);
        } finally {
            writer.shutdown();
        }
    }
}
