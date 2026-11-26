package dev.orderhub.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Hauptmenue. Der Holder erlaubt es dem Listener, eigene Inventare sicher zu erkennen. */
public final class OrderHubMenu implements InventoryHolder {

  public static final int SIZE = 27;
  public static final int REFRESH_SLOT = 13;

  private final Inventory inventory;

  public OrderHubMenu() {
    this.inventory = Bukkit.createInventory(this, SIZE, Component.text("OrderHub"));
    this.inventory.setItem(REFRESH_SLOT, new ItemStack(Material.PAPER));
  }

  public void open(Player player) {
    player.openInventory(inventory);
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "InventoryHolder#getInventory() must return the live inventory by contract")
  public @NotNull Inventory getInventory() {
    return inventory;
  }
}
