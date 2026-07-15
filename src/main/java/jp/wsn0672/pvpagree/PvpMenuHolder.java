package jp.wsn0672.pvpagree;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PvpMenuHolder implements InventoryHolder {
    public enum Type { RESPONSE, ALLOW_RESPONSE, SETTINGS, BLOCKLIST, ALLOWLIST }

    private final Type type;
    private final UUID sender;
    private final int page;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private Inventory inventory;

    public PvpMenuHolder(Type type, UUID sender) {
        this(type, sender, 0);
    }

    public PvpMenuHolder(Type type, UUID sender, int page) {
        this.type = type;
        this.sender = sender;
        this.page = page;
    }

    public Type type() { return type; }
    public UUID sender() { return sender; }
    public int page() { return page; }
    public Map<Integer, UUID> targets() { return targets; }
    public void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
