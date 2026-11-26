package dev.orderhub.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Basis aller OrderHub-Menues. Jedes Plugin-Inventar gehoert einem Menu (eigener {@link
 * InventoryHolder}), der zentrale {@link MenuListener} erkennt sie daran.
 *
 * <p>Alle Zugriffe laufen im Entity-Kontext des Spielers (Server-Thread auf Paper, Entity-Scheduler
 * auf Folia). Eingabeslots gibt nur die {@link SlotPolicy} frei; ihr Inhalt wird beim Beenden genau
 * einmal herausgenommen und zurueckgegeben.
 */
public abstract class Menu implements InventoryHolder {

  private final Inventory inventory;
  private final SlotPolicy slotPolicy;
  private final Map<Integer, Button> buttons = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  // Der Holder wird nur als Referenz im Inventar abgelegt, dort wird nichts aufgerufen.
  @SuppressWarnings("this-escape")
  protected Menu(Component title, int rows, SlotPolicy slotPolicy) {
    if (rows < 1 || rows > 6) {
      throw new IllegalArgumentException("rows must be 1..6: " + rows);
    }
    this.slotPolicy = Objects.requireNonNull(slotPolicy, "slotPolicy");
    this.inventory = Bukkit.createInventory(this, rows * 9, Objects.requireNonNull(title));
    for (int slot : slotPolicy.inputSlots()) {
      if (slot >= inventory.getSize()) {
        throw new IllegalArgumentException("input slot outside inventory: " + slot);
      }
    }
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "InventoryHolder#getInventory() must return the live inventory by contract")
  public @NotNull Inventory getInventory() {
    return inventory;
  }

  public final SlotPolicy slotPolicy() {
    return slotPolicy;
  }

  /** Setzt einen Button. Eingabeslots duerfen keine Buttons tragen. */
  protected final void setButton(int slot, Button button) {
    checkSlot(slot);
    if (slotPolicy.isInput(slot)) {
      throw new IllegalArgumentException("slot is an input slot: " + slot);
    }
    buttons.put(slot, Objects.requireNonNull(button, "button"));
    inventory.setItem(slot, button.render());
  }

  protected final void clearButton(int slot) {
    checkSlot(slot);
    if (buttons.remove(slot) != null) {
      inventory.setItem(slot, null);
    }
  }

  private void checkSlot(int slot) {
    if (slot < 0 || slot >= inventory.getSize()) {
      throw new IllegalArgumentException("slot outside inventory: " + slot);
    }
  }

  /** Liest alle Icons neu. */
  public final void refresh() {
    buttons.forEach((slot, button) -> inventory.setItem(slot, button.render()));
  }

  /** Aktueller Button an {@code slot} oder {@code null}. */
  final Button buttonAt(int slot) {
    return buttons.get(slot);
  }

  public final boolean isClosed() {
    return closed.get();
  }

  /** Setzt das Menue auf beendet. {@code true} nur fuer den ersten Aufruf. */
  final boolean markClosed() {
    return closed.compareAndSet(false, true);
  }

  /**
   * Nimmt den Inhalt aller Eingabeslots heraus (Slots werden geleert) und liefert Kopien. Ein
   * zweiter Aufruf liefert nichts, damit nichts doppelt zurueckgegeben wird.
   */
  final List<ItemStack> drainInputs() {
    List<ItemStack> items = new ArrayList<>();
    for (int slot : slotPolicy.inputSlots()) {
      ItemStack item = inventory.getItem(slot);
      if (item != null && !item.isEmpty()) {
        items.add(item.clone());
      }
      inventory.setItem(slot, null);
    }
    return items;
  }

  /**
   * Entschaerft das Menue beim Plugin-Disable: nimmt die Eingabeslot-Inhalte heraus und leert das
   * gesamte Inventar samt Buttons, damit keine Button-Items herausgenommen werden koennen.
   */
  final List<ItemStack> disarm() {
    List<ItemStack> items = drainInputs();
    buttons.clear();
    inventory.clear();
    return items;
  }

  /** Kopien des aktuellen Eingabeslot-Inhalts, ohne ihn zu entnehmen. */
  public final List<ItemStack> inputContents() {
    List<ItemStack> items = new ArrayList<>();
    for (int slot : slotPolicy.inputSlots()) {
      ItemStack item = inventory.getItem(slot);
      if (item != null && !item.isEmpty()) {
        items.add(item.clone());
      }
    }
    return items;
  }

  /** Wird direkt vor dem Oeffnen aufgerufen (Entity-Kontext). */
  protected void onOpen(Player player) {}

  /**
   * Menue wurde beendet und der Spieler ist noch benutzbar (Schliessen, Weltwechsel, Ersetzen,
   * Plugin-Disable). Die Eingabeslots sind zu diesem Zeitpunkt bereits geleert, die Items werden
   * danach zurueckgegeben.
   */
  protected void onClose(Player player, CloseReason reason) {}

  /**
   * Der Spieler hat die Sitzung verlassen (Disconnect, Kick, Tod) oder ist nicht mehr erreichbar.
   * Eingabeslots sind bereits geleert, die Items gehen ueber den ReturnSink.
   */
  protected void onAbandon(UUID playerId, CloseReason reason) {}
}
