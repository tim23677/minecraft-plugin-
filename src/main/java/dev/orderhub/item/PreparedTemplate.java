package dev.orderhub.item;

import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Einmal dekodierte Vorlage eines Auftrags. Menues erzeugen sie mit {@link
 * ItemMatcher#prepare(dev.orderhub.domain.ItemSnapshot)} einmal beim Oeffnen und vergleichen damit
 * jeden Slot, statt die Vorlage bei jedem Vergleich neu zu dekodieren. Die Vorlage wird nie
 * veraendert.
 *
 * @since 2.0.0
 */
public final class PreparedTemplate {

  private final ItemStack stack;

  PreparedTemplate(ItemStack stack) {
    this.stack = Objects.requireNonNull(stack, "stack");
  }

  ItemStack stack() {
    return stack;
  }

  public Material material() {
    return stack.getType();
  }
}
