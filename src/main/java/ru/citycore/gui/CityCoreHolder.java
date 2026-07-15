package ru.citycore.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class CityCoreHolder implements InventoryHolder {
    private final Route route;
    private Inventory inventory;

    public CityCoreHolder(Route route) { this.route = Objects.requireNonNull(route, "route"); }
    public Route route() { return route; }
    public void bind(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Inventory уже привязан");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }
    @Override public @NotNull Inventory getInventory() {
        return Objects.requireNonNull(inventory, "Inventory ещё не привязан");
    }
}
