package dev.orderhub.gui;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Bestaetigen oder Abbrechen. Genau einer der beiden Callbacks laeuft, hoechstens einmal. Wird das
 * Menue ohne Entscheidung beendet (Schliessen, Disconnect, ...), gilt das als Abbruch.
 */
public final class ConfirmMenu extends Menu {

  public static final int CONFIRM_SLOT = 11;
  public static final int CANCEL_SLOT = 15;

  private final AtomicBoolean decided = new AtomicBoolean();
  private final Consumer<Player> onConfirm;
  private final Consumer<UUID> onCancel;

  public ConfirmMenu(
      Component title,
      ItemStack confirmIcon,
      ItemStack cancelIcon,
      Consumer<Player> onConfirm,
      Consumer<UUID> onCancel) {
    super(title, 3, SlotPolicy.none());
    this.onConfirm = Objects.requireNonNull(onConfirm, "onConfirm");
    this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
    setButton(CONFIRM_SLOT, Button.of(confirmIcon, this::confirm));
    setButton(CANCEL_SLOT, Button.of(cancelIcon, this::cancel));
  }

  private void confirm(ClickContext context) {
    if (decided.compareAndSet(false, true)) {
      try {
        onConfirm.accept(context.player());
      } finally {
        context.player().closeInventory();
      }
    }
  }

  private void cancel(ClickContext context) {
    if (decided.compareAndSet(false, true)) {
      try {
        onCancel.accept(context.player().getUniqueId());
      } finally {
        context.player().closeInventory();
      }
    }
  }

  @Override
  protected void onClose(Player player, CloseReason reason) {
    cancelIfUndecided(player.getUniqueId());
  }

  @Override
  protected void onAbandon(UUID playerId, CloseReason reason) {
    cancelIfUndecided(playerId);
  }

  private void cancelIfUndecided(UUID playerId) {
    if (decided.compareAndSet(false, true)) {
      onCancel.accept(playerId);
    }
  }

  public boolean isDecided() {
    return decided.get();
  }
}
