package dev.orderhub.gui;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.bukkit.inventory.ItemStack;

/**
 * Legt fest, welche Slots eines Menues Items aufnehmen und abgeben duerfen. Standard: kein Slot.
 * Jeder Eingabeslot hat eine serverseitige Validierungsfunktion fuer jedes Item.
 */
public final class SlotPolicy {

  private static final SlotPolicy NONE = new SlotPolicy(Map.of());

  private final Map<Integer, Predicate<ItemStack>> inputs;

  private SlotPolicy(Map<Integer, Predicate<ItemStack>> inputs) {
    this.inputs = Collections.unmodifiableMap(new TreeMap<>(inputs));
  }

  /** Kein Slot nimmt Items auf. */
  public static SlotPolicy none() {
    return NONE;
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isInput(int slot) {
    return inputs.containsKey(slot);
  }

  /**
   * {@code true}, wenn {@code slot} ein Eingabeslot ist und {@code item} die Validierung besteht.
   */
  public boolean accepts(int slot, ItemStack item) {
    Predicate<ItemStack> validator = inputs.get(slot);
    return validator != null && item != null && !item.isEmpty() && validator.test(item.clone());
  }

  /** Eingabeslots, aufsteigend sortiert. */
  public Set<Integer> inputSlots() {
    return inputs.keySet();
  }

  /** Baut eine {@link SlotPolicy}. */
  public static final class Builder {
    private final Map<Integer, Predicate<ItemStack>> inputs = new TreeMap<>();

    private Builder() {}

    /** Gibt {@code slot} als Eingabeslot frei; {@code validator} prueft jedes Item serverseitig. */
    public Builder input(int slot, Predicate<ItemStack> validator) {
      if (slot < 0) {
        throw new IllegalArgumentException("slot must be >= 0");
      }
      inputs.put(slot, Objects.requireNonNull(validator, "validator"));
      return this;
    }

    public SlotPolicy build() {
      return inputs.isEmpty() ? NONE : new SlotPolicy(inputs);
    }
  }
}
