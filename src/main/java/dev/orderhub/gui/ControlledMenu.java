package dev.orderhub.gui;

import net.kyori.adventure.text.Component;

/**
 * Menue ohne eigene Eingabeslots, dessen Buttons der Aufrufer frei setzt und austauscht (Listen mit
 * Seiten, Auswahlen, Statistiken). Alle Slots sind gesperrt, nur Buttons reagieren.
 *
 * @since 2.0.0
 */
public final class ControlledMenu extends Menu {

  public ControlledMenu(Component title, int rows) {
    super(title, rows, SlotPolicy.none());
  }

  /** Setzt oder ersetzt den Button in {@code slot}. */
  public void put(int slot, Button button) {
    setButton(slot, button);
  }

  /** Entfernt den Button in {@code slot}, falls vorhanden. */
  public void remove(int slot) {
    clearButton(slot);
  }
}
