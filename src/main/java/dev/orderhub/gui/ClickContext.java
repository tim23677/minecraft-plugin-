package dev.orderhub.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * Kontext eines Button-Klicks. Der Cursor ist eine Kopie, Aenderungen daran wirken nicht zurueck.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Player und Menu sind Live-Referenzen des Servers und gewollt direkt nutzbar")
public final class ClickContext {

  private final Player player;
  private final ClickType clickType;
  private final int slot;
  private final ItemStack cursor;
  private final Menu menu;

  ClickContext(Player player, ClickType clickType, int slot, ItemStack cursor, Menu menu) {
    this.player = Objects.requireNonNull(player, "player");
    this.clickType = Objects.requireNonNull(clickType, "clickType");
    this.slot = slot;
    this.cursor = cursor == null ? new ItemStack(org.bukkit.Material.AIR) : cursor.clone();
    this.menu = Objects.requireNonNull(menu, "menu");
  }

  public Player player() {
    return player;
  }

  public ClickType clickType() {
    return clickType;
  }

  public int slot() {
    return slot;
  }

  /** Kopie des Cursor-Items (AIR, wenn leer). */
  public ItemStack cursor() {
    return cursor.clone();
  }

  public Menu menu() {
    return menu;
  }
}
