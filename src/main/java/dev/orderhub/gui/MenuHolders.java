package dev.orderhub.gui;

import org.bukkit.inventory.Inventory;

/** Ermittelt das {@link Menu} eines Inventars, ohne unnoetig Block-Snapshots zu erzeugen. */
final class MenuHolders {

  private MenuHolders() {}

  /** Das Menue, dem {@code inventory} gehoert, sonst {@code null}. */
  static Menu of(Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    Object holder;
    try {
      holder = inventory.getHolder(false);
    } catch (RuntimeException e) {
      // MockBukkit wirft UnimplementedOperationException (keine UnsupportedOperationException).
      holder = inventory.getHolder();
    }
    return holder instanceof Menu menu ? menu : null;
  }
}
