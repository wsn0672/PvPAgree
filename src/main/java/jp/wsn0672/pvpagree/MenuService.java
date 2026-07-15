package jp.wsn0672.pvpagree;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.UUID;

public final class MenuService {
    private static final int BLOCKS_PER_PAGE = 45;
    private final PvpAgreePlugin plugin;
    private final PvpManager manager;
    private final PreferenceStore preferences;
    private final MessageService messages;

    public MenuService(PvpAgreePlugin plugin, PvpManager manager, PreferenceStore preferences,
                       MessageService messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.preferences = preferences;
        this.messages = messages;
    }

    public void openDefault(Player player) {
        Optional<PvpRequest> request = manager.latestIncoming(player.getUniqueId());
        if (request.isPresent()) {
            openResponse(player, request.get());
            return;
        }
        manager.latestAllowIncoming(player.getUniqueId()).ifPresentOrElse(
                allowRequest -> openAllowResponse(player, allowRequest), () -> openSettings(player));
    }

    private void openAllowResponse(Player target, PvpRequest request) {
        String senderName = playerName(request.sender());
        PvpMenuHolder holder = new PvpMenuHolder(PvpMenuHolder.Type.ALLOW_RESPONSE, request.sender());
        int size = inventorySize("gui.allow-response.size", 27);
        Inventory inventory = Bukkit.createInventory(holder, size,
                messages.component("gui.allow-response-title", Map.of("player", senderName)));
        holder.inventory(inventory);
        Map<String, String> values = Map.of("player", senderName);
        setConfigured(inventory, "gui.allow-response.accept", "gui.allow-response-accept",
                Material.LIME_WOOL, 11, values);
        setConfigured(inventory, "gui.allow-response.deny", "gui.allow-response-deny",
                Material.RED_WOOL, 15, values);
        target.openInventory(inventory);
    }

    private void openResponse(Player target, PvpRequest request) {
        String senderName = playerName(request.sender());
        PvpMenuHolder holder = new PvpMenuHolder(PvpMenuHolder.Type.RESPONSE, request.sender());
        int size = inventorySize("gui.response.size", 27);
        Inventory inventory = Bukkit.createInventory(holder, size,
                messages.component("gui.response-title", Map.of("player", senderName)));
        holder.inventory(inventory);
        Map<String, String> placeholders = Map.of("player", senderName);
        setConfigured(inventory, "gui.response.accept", "gui.accept", Material.LIME_WOOL, 11, placeholders);
        setConfigured(inventory, "gui.response.deny", "gui.deny", Material.RED_WOOL, 13, placeholders);
        setConfigured(inventory, "gui.response.block", "gui.block", Material.BARRIER, 15, placeholders);
        target.openInventory(inventory);
    }

    public void openSettings(Player player) {
        PlayerPreference selected = preferences.getPreference(player.getUniqueId());
        PvpMenuHolder holder = new PvpMenuHolder(PvpMenuHolder.Type.SETTINGS, null);
        int size = inventorySize("gui.settings.size", 27);
        Inventory inventory = Bukkit.createInventory(holder, size, messages.component("gui.settings-title"));
        holder.inventory(inventory);
        setSetting(inventory, "auto-accept", selected == PlayerPreference.AUTO_ACCEPT, Material.LIME_DYE, 10);
        setSetting(inventory, "show", selected == PlayerPreference.SHOW, Material.PAPER, 12);
        setSetting(inventory, "always-deny", selected == PlayerPreference.ALWAYS_DENY, Material.RED_DYE, 14);

        boolean gesture = preferences.isGestureEnabled(player.getUniqueId());
        Map<String, String> gestureValues = Map.of(
                "state", messages.plain(gesture ? "gui.enabled" : "gui.disabled", Map.of()),
                "required", Integer.toString(Math.max(1,
                        plugin.getConfig().getInt("sneak-gesture.required-swings", 5))));
        setConfigured(inventory, "gui.settings.gesture", "gui.gesture",
                gesture ? Material.LEVER : Material.STONE_BUTTON, 20, gestureValues);
        setConfigured(inventory, "gui.settings.blocklist", "gui.blocklist", Material.PLAYER_HEAD, 24,
                Map.of("count", Integer.toString(preferences.getBlocked(player.getUniqueId()).size())));
        setConfigured(inventory, "gui.settings.allowlist", "gui.allowlist", Material.TOTEM_OF_UNDYING, 22,
                Map.of("count", Integer.toString(preferences.getMutuallyAlwaysAllowed(player.getUniqueId()).size())));
        player.openInventory(inventory);
    }

    private void setSetting(Inventory inventory, String key, boolean selected, Material fallback, int fallbackSlot) {
        String marker = plugin.getConfig().getString(selected ? "gui.selected" : "gui.not-selected", "");
        setConfigured(inventory, "gui.settings." + key, "gui." + key, fallback, fallbackSlot,
                Map.of("selected", marker));
    }

    public void openBlocklist(Player player, int requestedPage) {
        List<UUID> blocked = preferences.getBlocked(player.getUniqueId());
        int maxPage = Math.max(0, (blocked.size() - 1) / BLOCKS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        PvpMenuHolder holder = new PvpMenuHolder(PvpMenuHolder.Type.BLOCKLIST, null, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.component("gui.blocklist-title",
                Map.of("page", Integer.toString(page + 1), "pages", Integer.toString(maxPage + 1))));
        holder.inventory(inventory);
        int from = page * BLOCKS_PER_PAGE;
        int to = Math.min(blocked.size(), from + BLOCKS_PER_PAGE);
        for (int index = from; index < to; index++) {
            UUID uuid = blocked.get(index);
            int slot = index - from;
            holder.targets().put(slot, uuid);
            inventory.setItem(slot, playerHead(uuid));
        }
        if (blocked.isEmpty()) {
            setConfigured(inventory, "gui.blocklist-navigation.empty", "gui.empty-blocklist",
                    Material.PAPER, 22, Map.of());
        }
        if (page > 0) setConfigured(inventory, "gui.blocklist-navigation.previous", "gui.previous",
                Material.ARROW, 45, Map.of());
        setConfigured(inventory, "gui.blocklist-navigation.back", "gui.back",
                Material.BARRIER, 49, Map.of());
        if (page < maxPage) setConfigured(inventory, "gui.blocklist-navigation.next", "gui.next",
                Material.ARROW, 53, Map.of());
        player.openInventory(inventory);
    }

    public void openAllowlist(Player player, int requestedPage) {
        Set<UUID> candidates = new LinkedHashSet<>(preferences.getMutuallyAlwaysAllowed(player.getUniqueId()));
        Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                .map(Player::getUniqueId).forEach(candidates::add);
        List<UUID> players = candidates.stream()
                .sorted(Comparator.comparing(this::playerName, String.CASE_INSENSITIVE_ORDER)).toList();
        int maxPage = Math.max(0, (players.size() - 1) / BLOCKS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        PvpMenuHolder holder = new PvpMenuHolder(PvpMenuHolder.Type.ALLOWLIST, null, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.component("gui.allowlist-title",
                Map.of("page", Integer.toString(page + 1), "pages", Integer.toString(maxPage + 1))));
        holder.inventory(inventory);
        int from = page * BLOCKS_PER_PAGE;
        int to = Math.min(players.size(), from + BLOCKS_PER_PAGE);
        for (int index = from; index < to; index++) {
            UUID uuid = players.get(index);
            int slot = index - from;
            holder.targets().put(slot, uuid);
            inventory.setItem(slot, allowlistHead(player.getUniqueId(), uuid));
        }
        if (players.isEmpty()) {
            setConfigured(inventory, "gui.allowlist-navigation.empty", "gui.empty-allowlist",
                    Material.PAPER, 22, Map.of());
        }
        if (page > 0) setConfigured(inventory, "gui.allowlist-navigation.previous", "gui.previous",
                Material.ARROW, 45, Map.of());
        setConfigured(inventory, "gui.allowlist-navigation.back", "gui.back", Material.BARRIER, 49, Map.of());
        if (page < maxPage) setConfigured(inventory, "gui.allowlist-navigation.next", "gui.next",
                Material.ARROW, 53, Map.of());
        player.openInventory(inventory);
    }

    private ItemStack allowlistHead(UUID owner, UUID target) {
        boolean allowed = preferences.isMutuallyAlwaysAllowed(owner, target);
        boolean pending = manager.hasOutgoingAllowRequest(owner, target);
        ItemStack item = new ItemStack(material("gui.allowlist-entry.material", Material.PLAYER_HEAD));
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        String statePath = allowed ? "gui.allowed" : pending ? "gui.allow-pending" : "gui.not-allowed";
        String actionPath = allowed ? "gui.remove-allow" : pending ? "gui.allow-pending-action" : "gui.add-allow";
        Map<String, String> values = Map.of("player", playerName(target),
                "state", messages.plain(statePath, Map.of()), "action", messages.plain(actionPath, Map.of()));
        meta.displayName(messages.component("gui.allowlist-entry.name", values));
        meta.lore(lore("gui.allowlist-entry.lore", values));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack playerHead(UUID uuid) {
        ItemStack item = new ItemStack(material("gui.blocklist-entry.material", Material.PLAYER_HEAD));
        ItemMeta rawMeta = item.getItemMeta();
        String name = playerName(uuid);
        if (rawMeta instanceof SkullMeta meta) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(offline);
        }
        rawMeta.displayName(messages.component("gui.blocklist-entry.name", Map.of("player", name)));
        rawMeta.lore(lore("gui.blocklist-entry.lore", Map.of("player", name)));
        item.setItemMeta(rawMeta);
        return item;
    }

    private void setConfigured(Inventory inventory, String configRoot, String textRoot, Material fallback,
                               int fallbackSlot, Map<String, String> placeholders) {
        int slot = plugin.getConfig().getInt(configRoot + ".slot", fallbackSlot);
        if (slot < 0 || slot >= inventory.getSize()) {
            plugin.getLogger().warning(configRoot + ".slot がGUIの範囲外です: " + slot);
            return;
        }
        inventory.setItem(slot, item(material(configRoot + ".material", fallback), textRoot, placeholders));
    }

    private ItemStack item(Material material, String textRoot, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component(textRoot + "-name", placeholders));
        meta.lore(lore(textRoot + "-lore", placeholders));
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lore(String path, Map<String, String> placeholders) {
        List<Component> result = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(path)) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                line = line.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            result.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        return result;
    }

    public void clickResponse(Player player, UUID sender, int slot) {
        int accept = plugin.getConfig().getInt("gui.response.accept.slot", 11);
        int deny = plugin.getConfig().getInt("gui.response.deny.slot", 13);
        int block = plugin.getConfig().getInt("gui.response.block.slot", 15);
        if (slot != accept && slot != deny && slot != block) return;
        player.closeInventory();
        if (slot == accept) manager.accept(player, sender);
        else if (slot == deny) manager.deny(player, sender, false);
        else manager.deny(player, sender, true);
    }

    public void clickAllowResponse(Player player, UUID sender, int slot) {
        int accept = plugin.getConfig().getInt("gui.allow-response.accept.slot", 11);
        int deny = plugin.getConfig().getInt("gui.allow-response.deny.slot", 15);
        if (slot != accept && slot != deny) return;
        player.closeInventory();
        if (slot == accept) manager.acceptAlwaysAllow(player, sender);
        else manager.denyAlwaysAllow(player, sender);
    }

    public void clickSetting(Player player, int slot) {
        String key = null;
        PlayerPreference preference = null;
        if (slot == plugin.getConfig().getInt("gui.settings.auto-accept.slot", 10)) {
            key = "auto-accept"; preference = PlayerPreference.AUTO_ACCEPT;
        } else if (slot == plugin.getConfig().getInt("gui.settings.show.slot", 12)) {
            key = "show"; preference = PlayerPreference.SHOW;
        } else if (slot == plugin.getConfig().getInt("gui.settings.always-deny.slot", 14)) {
            key = "always-deny"; preference = PlayerPreference.ALWAYS_DENY;
        } else if (slot == plugin.getConfig().getInt("gui.settings.gesture.slot", 20)) {
            boolean enabled = !preferences.isGestureEnabled(player.getUniqueId());
            preferences.setGestureEnabled(player.getUniqueId(), enabled);
            messages.send(player, "gesture-changed", Map.of("state", messages.plain(
                    enabled ? "gui.enabled" : "gui.disabled", Map.of())));
            openSettings(player);
            return;
        } else if (slot == plugin.getConfig().getInt("gui.settings.blocklist.slot", 24)) {
            openBlocklist(player, 0);
            return;
        } else if (slot == plugin.getConfig().getInt("gui.settings.allowlist.slot", 22)) {
            openAllowlist(player, 0);
            return;
        }
        if (preference == null) return;
        preferences.setPreference(player.getUniqueId(), preference);
        messages.send(player, "setting-changed", Map.of("setting", messages.plain("gui." + key + "-name", Map.of())));
        openSettings(player);
    }

    public void clickBlocklist(Player player, PvpMenuHolder holder, int slot) {
        UUID blocked = holder.targets().get(slot);
        if (blocked != null) {
            preferences.unblock(player.getUniqueId(), blocked);
            messages.send(player, "player-unblocked", Map.of("player", playerName(blocked)));
            openBlocklist(player, holder.page());
        } else if (slot == plugin.getConfig().getInt("gui.blocklist-navigation.previous.slot", 45)
                && holder.page() > 0) {
            openBlocklist(player, holder.page() - 1);
        } else if (slot == plugin.getConfig().getInt("gui.blocklist-navigation.back.slot", 49)) {
            openSettings(player);
        } else if (slot == plugin.getConfig().getInt("gui.blocklist-navigation.next.slot", 53)) {
            openBlocklist(player, holder.page() + 1);
        }
    }

    public void clickAllowlist(Player player, PvpMenuHolder holder, int slot) {
        UUID target = holder.targets().get(slot);
        if (target != null) {
            if (preferences.isMutuallyAlwaysAllowed(player.getUniqueId(), target)) {
                manager.removeAlwaysAllowed(player, target);
            } else {
                Player onlineTarget = Bukkit.getPlayer(target);
                if (onlineTarget == null) {
                    messages.send(player, "allow-target-offline", Map.of("player", playerName(target)));
                } else {
                    manager.requestAlwaysAllow(player, onlineTarget);
                }
            }
            openAllowlist(player, holder.page());
        } else if (slot == plugin.getConfig().getInt("gui.allowlist-navigation.previous.slot", 45)
                && holder.page() > 0) {
            openAllowlist(player, holder.page() - 1);
        } else if (slot == plugin.getConfig().getInt("gui.allowlist-navigation.back.slot", 49)) {
            openSettings(player);
        } else if (slot == plugin.getConfig().getInt("gui.allowlist-navigation.next.slot", 53)) {
            openAllowlist(player, holder.page() + 1);
        }
    }

    public void allowPlayer(Player owner, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(owner, "player-not-found", Map.of("player", targetName));
            return;
        }
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            messages.send(owner, "cannot-allow-self");
            return;
        }
        if (preferences.isMutuallyAlwaysAllowed(owner.getUniqueId(), target.getUniqueId())) {
            messages.send(owner, "already-always-allowed", Map.of("player", target.getName()));
            return;
        }
        manager.requestAlwaysAllow(owner, target);
    }

    public void unallowPlayer(Player owner, String targetName) {
        Optional<UUID> target = preferences.getMutuallyAlwaysAllowed(owner.getUniqueId()).stream()
                .filter(uuid -> playerName(uuid).equalsIgnoreCase(targetName)).findFirst();
        if (target.isEmpty()) {
            messages.send(owner, "not-always-allowed", Map.of("player", targetName));
            return;
        }
        manager.removeAlwaysAllowed(owner, target.get());
    }

    private Material material(String path, Material fallback) {
        Material value = Material.matchMaterial(plugin.getConfig().getString(path, fallback.name()));
        return value == null || !value.isItem() ? fallback : value;
    }

    private int inventorySize(String path, int fallback) {
        int configured = plugin.getConfig().getInt(path, fallback);
        if (configured < 9 || configured > 54 || configured % 9 != 0) return fallback;
        return configured;
    }

    private String playerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }
}
