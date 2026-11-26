package dev.orderhub.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Sperrt jede Item-Bewegung in OrderHub-Menues und behandelt Klicks. */
public final class OrderHubMenuListener implements Listener {

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof OrderHubMenu)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() == event.getView().getTopInventory()
        && event.getSlot() == OrderHubMenu.REFRESH_SLOT) {
      event.getWhoClicked().sendMessage("OrderHub refreshed");
    }
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof OrderHubMenu) {
      event.setCancelled(true);
    }
  }
}
