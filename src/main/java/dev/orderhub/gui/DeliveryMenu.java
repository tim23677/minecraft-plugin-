package dev.orderhub.gui;

import dev.orderhub.item.PreparedTemplate;
import dev.orderhub.item.Tolerance;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Liefermenue: Eingabeslots nehmen nur Items an, die der Validator akzeptiert. Bestaetigen und
 * Abbrechen sind Buttons, die uebrigen Slots bleiben gesperrt. Nicht angenommene oder nicht
 * abgeschlossene Items gibt das Framework beim Schliessen zurueck.
 */
public final class DeliveryMenu extends Menu {

  public static final int INFO_SLOT = 49;
  public static final int CONFIRM_SLOT = 53;
  public static final int CANCEL_SLOT = 45;

  private volatile PreparedTemplate template;

  /** Die einmal je Menue dekodierte Vorlage des Auftrags (gesetzt vor dem Oeffnen). */
  public PreparedTemplate template() {
    return template;
  }

  public void template(PreparedTemplate value) {
    this.template = value;
  }

  private volatile Tolerance tolerance = Tolerance.EXACT;

  /** Toleranz des Auftrags fuer den Abgleich (gesetzt vor dem Oeffnen). */
  public Tolerance tolerance() {
    return tolerance;
  }

  public void tolerance(Tolerance value) {
    this.tolerance = value;
  }

  public DeliveryMenu(
      Component title,
      List<Integer> inputSlots,
      Predicate<ItemStack> validator,
      Supplier<ItemStack> infoIcon,
      ItemStack confirmIcon,
      ItemStack cancelIcon,
      Consumer<DeliveryMenu> onConfirm,
      Consumer<Player> onCancel) {
    super(title, 6, policy(inputSlots, validator));
    setButton(INFO_SLOT, Button.of(infoIcon, context -> context.menu().refresh()));
    setButton(
        CONFIRM_SLOT,
        Button.of(
            confirmIcon,
            context -> {
              if (!isFrozen()) {
                onConfirm.accept(this);
              }
            }));
    setButton(
        CANCEL_SLOT,
        Button.of(
            cancelIcon,
            context -> {
              if (!isFrozen()) {
                onCancel.accept(context.player());
              }
            }));
  }

  private static SlotPolicy policy(List<Integer> slots, Predicate<ItemStack> validator) {
    SlotPolicy.Builder builder = SlotPolicy.builder();
    for (int slot : slots) {
      if (slot == INFO_SLOT || slot == CONFIRM_SLOT || slot == CANCEL_SLOT) {
        throw new IllegalArgumentException("input slot collides with a button: " + slot);
      }
      builder.input(slot, validator);
    }
    return builder.build();
  }
}
