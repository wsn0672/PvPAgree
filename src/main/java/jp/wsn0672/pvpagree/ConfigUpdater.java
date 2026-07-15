package jp.wsn0672.pvpagree;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 既存値を保持したまま、jar内のデフォルト設定にしかない項目を補完する。 */
public final class ConfigUpdater {
    private ConfigUpdater() {
    }

    public static int addMissingDefaults(PvpAgreePlugin plugin) {
        InputStream resource = plugin.getResource("config.yml");
        if (resource == null) {
            plugin.getLogger().warning("jar内のconfig.ymlを読み込めませんでした。");
            return 0;
        }
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception exception) {
            plugin.getLogger().warning("デフォルトconfig.ymlの読み込みに失敗しました: " + exception.getMessage());
            return 0;
        }

        FileConfiguration current = plugin.getConfig();
        int added = 0;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || current.contains(path, true)) continue;
            current.set(path, defaults.get(path));
            List<String> comments = defaults.getComments(path);
            if (!comments.isEmpty()) current.setComments(path, comments);
            List<String> inlineComments = defaults.getInlineComments(path);
            if (!inlineComments.isEmpty()) current.setInlineComments(path, inlineComments);
            added++;
        }
        if (added > 0) {
            plugin.saveConfig();
            plugin.getLogger().info("config.ymlに不足していた設定を" + added + "項目追加しました。既存値は保持されています。");
        }
        return added;
    }
}
