package jp.wsn0672.pvpagree;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvpAgreePlugin extends JavaPlugin {
    private PreferenceStore preferences;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.addMissingDefaults(this);
        preferences = new PreferenceStore(this);
        MessageService messages = new MessageService(this);
        WebhookService webhook = new WebhookService(this);
        PvpManager manager = new PvpManager(this, preferences, messages, webhook);
        MenuService menus = new MenuService(this, manager, preferences, messages);
        PvpCommand command = new PvpCommand(this, manager, menus, messages);

        PluginCommand pvpCommand = getCommand("pvp");
        if (pvpCommand == null) {
            throw new IllegalStateException("plugin.ymlにpvpコマンドがありません。");
        }
        pvpCommand.setExecutor(command);
        pvpCommand.setTabCompleter(command);
        PvpListener listener = new PvpListener(this, manager, menus, messages, preferences);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getScheduler().runTaskTimer(this, manager::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, listener::tickHeadGestures, 2L, 2L);
        getLogger().info("PvpAgreeを有効化しました。");
    }

    public int reloadAndUpdateConfig() {
        reloadConfig();
        return ConfigUpdater.addMissingDefaults(this);
    }

    @Override
    public void onDisable() {
        if (preferences != null) preferences.close();
    }
}
