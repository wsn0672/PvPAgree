package jp.wsn0672.pvpagree;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class MessageService {
    private final PvpAgreePlugin plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public MessageService(PvpAgreePlugin plugin) {
        this.plugin = plugin;
    }

    public Component component(String path, Map<String, String> placeholders) {
        String text = plugin.getConfig().getString(path, path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return legacy.deserialize(text);
    }

    public Component component(String path) {
        return component(path, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component("messages.prefix").append(component("messages." + key, placeholders)));
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public String plain(String path, Map<String, String> placeholders) {
        String text = plugin.getConfig().getString(path, path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text.replaceAll("&[0-9a-fk-or]", "");
    }
}
