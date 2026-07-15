package ru.citycore.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public record CityCoreHolder(Route route) implements InventoryHolder {
    @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException("Virtual holder"); }
}
