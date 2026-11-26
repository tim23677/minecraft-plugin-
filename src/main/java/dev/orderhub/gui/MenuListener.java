package dev.orderhub.gui;

import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Zentraler Listener fuer alle OrderHub-Menues und Sicherheitsgrenze gegen Item-Duplikation.
 *
 * <p>Grundsatz: Ist das obere Inventar ein {@link Menu}, wird jeder Klick abgebrochen, ausser er
 * ist ausdruecklich erlaubt. Erlaubt sind nur (1) schlichte Links-/Rechtsklicks im Spielerinventar
 * und (2) schlichte Links-/Rechtsklicks auf einen von der {@link SlotPolicy} freigegebenen
 * Eingabeslot, wenn das Item die serverseitige Validierung besteht. Shift-Klick, Zahlentasten,
 * Offhand-Tausch, Doppelklick, Drop, Klick ausserhalb, Mittelklick und Kreativ-Klicks sind immer
 * gesperrt. Drags ueber Menue-Slots sind gesperrt. Alle Handler laufen mit hoechster Prioritaet und
 * {@code ignoreCancelled = false}.
 */
public final class MenuListener implements Listener {

  /** Aktionen, die nur innerhalb eines einzelnen Inventars Stacks verschieben. */
  private static final Set<InventoryAction> PLAIN_ACTIONS =
      Set.of(
          InventoryAction.PICKUP_ALL,
          InventoryAction.PICKUP_HALF,
          InventoryAction.PICKUP_ONE,
          InventoryAction.PICKUP_SOME,
          InventoryAction.PLACE_ALL,
          InventoryAction.PLACE_ONE,
          InventoryAction.PLACE_SOME,
          InventoryAction.SWAP_WITH_CURSOR);

  private static final Set<InventoryAction> PLACING_ACTIONS =
      Set.of(
          InventoryAction.PLACE_ALL,
          InventoryAction.PLACE_ONE,
          InventoryAction.PLACE_SOME,
          InventoryAction.SWAP_WITH_CURSOR);

  private final MenuManager manager;
  private final Logger logger;

  public MenuListener(MenuManager manager, Logger logger) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  private static Menu menuOf(Inventory inventory) {
    return MenuHolders.of(inventory);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onClick(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    Menu menu = menuOf(top);
    if (menu == null) {
      return;
    }
    if (manager.isDisabled()) {
      deny(event); // fail closed: nach closeAll bleibt jeder Klick auf OrderHub-Inventare gesperrt
      return;
    }
    int raw = event.getRawSlot();
    boolean outside = raw < 0 || event.getSlotType() == SlotType.OUTSIDE;
    boolean plain = isPlain(event.getClick(), event.getAction());
    if (outside || !plain) {
      deny(event);
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      deny(event);
      return;
    }
    if (raw >= top.getSize()) {
      return; // schlichter Klick im Spielerinventar: bewegt nichts ins Menue
    }
    handleMenuSlot(event, menu, player, raw);
  }

  private static boolean isPlain(ClickType click, InventoryAction action) {
    return (click == ClickType.LEFT || click == ClickType.RIGHT) && PLAIN_ACTIONS.contains(action);
  }

  private void handleMenuSlot(InventoryClickEvent event, Menu menu, Player player, int slot) {
    if (!manager.isCurrent(player, menu)) {
      deny(event);
      return;
    }
    SlotPolicy policy = menu.slotPolicy();
    if (policy.isInput(slot)) {
      ItemStack placed = event.getCursor();
      boolean placing = PLACING_ACTIONS.contains(event.getAction());
      if (placing && !policy.accepts(slot, placed)) {
        deny(event);
      }
      return; // erlaubter Eingabeslot: Vanilla-Verhalten, Bilanz sichert das Beenden des Menues
    }
    deny(event);
    dispatchButton(event, menu, player, slot);
  }

  private void dispatchButton(InventoryClickEvent event, Menu menu, Player player, int slot) {
    Button button = menu.buttonAt(slot);
    if (button == null || !manager.acquireClick(player.getUniqueId())) {
      return;
    }
    try {
      button
          .handler()
          .onClick(new ClickContext(player, event.getClick(), slot, event.getCursor(), menu));
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE, "button handler failed in slot " + slot, e);
    }
  }

  /**
   * Fuer den Fall "Kreativ-Klick": eigenes Event, wird nicht ueber InventoryClickEvent geliefert.
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onCreative(InventoryCreativeEvent event) {
    if (menuOf(event.getView().getTopInventory()) != null) {
      deny(event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDrag(InventoryDragEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (menuOf(top) == null) {
      return;
    }
    for (int raw : event.getRawSlots()) {
      if (raw < top.getSize()) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onMove(InventoryMoveItemEvent event) {
    if (menuOf(event.getSource()) != null
        || menuOf(event.getDestination()) != null
        || menuOf(event.getInitiator()) != null) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onClose(InventoryCloseEvent event) {
    if (event.getPlayer() instanceof Player player) {
      manager.handleClose(player, event.getInventory(), mapReason(event.getReason()));
    }
  }

  private static CloseReason mapReason(InventoryCloseEvent.Reason reason) {
    return switch (reason) {
      case DISCONNECT -> CloseReason.DISCONNECT;
      case DEATH -> CloseReason.DEATH;
      default -> CloseReason.PLAYER_CLOSE;
    };
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    manager.abandon(event.getPlayer(), CloseReason.DISCONNECT);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onKick(PlayerKickEvent event) {
    manager.abandon(event.getPlayer(), CloseReason.KICK);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    manager.abandon(event.getEntity(), CloseReason.DEATH);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onWorldChange(PlayerChangedWorldEvent event) {
    manager.abandon(event.getPlayer(), CloseReason.WORLD_CHANGE);
  }

  private static void deny(InventoryClickEvent event) {
    event.setResult(org.bukkit.event.Event.Result.DENY);
    event.setCancelled(true);
  }
}
