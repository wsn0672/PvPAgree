package jp.wsn0672.pvpagree;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PvpCommand implements CommandExecutor, TabCompleter {
    private final PvpAgreePlugin plugin;
    private final PvpManager manager;
    private final MenuService menus;
    private final MessageService messages;

    public PvpCommand(PvpAgreePlugin plugin, PvpManager manager, MenuService menus, MessageService messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.menus = menus;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("pvpagree.reload")) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reloadAndUpdateConfig();
            manager.handleConfigReload();
            messages.send(sender, "config-reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            menus.openDefault(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("blocklist")) {
            menus.openBlocklist(player, 0);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("allowlist")) {
            menus.openAllowlist(player, 0);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("allow")) {
            menus.allowPlayer(player, args[1]);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unallow")) {
            menus.unallowPlayer(player, args[1]);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancelall")) {
            manager.cancelAll(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            manager.cancel(player, args[1]);
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            UUID requestSender;
            try {
                requestSender = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                messages.send(player, "no-request");
                return true;
            }
            if (args[0].equalsIgnoreCase("accept")) manager.accept(player, requestSender);
            else manager.deny(player, requestSender, false);
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("allowaccept")
                || args[0].equalsIgnoreCase("allowdeny"))) {
            UUID requestSender;
            try {
                requestSender = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                messages.send(player, "no-allow-request");
                return true;
            }
            if (args[0].equalsIgnoreCase("allowaccept")) manager.acceptAlwaysAllow(player, requestSender);
            else manager.denyAlwaysAllow(player, requestSender);
            return true;
        }
        if (args.length != 1) {
            messages.send(player, "usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "player-not-found", Map.of("player", args[0]));
            return true;
        }
        manager.request(player, target);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (sender.hasPermission("pvpagree.reload") && "reload".startsWith(prefix)) result.add("reload");
        for (String subcommand : List.of("allow", "allowlist", "unallow", "blocklist", "cancel", "cancelall")) {
            if (subcommand.startsWith(prefix)) result.add(subcommand);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getName().equals(sender.getName()) && player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(player.getName());
            }
        }
        return result;
    }
}
