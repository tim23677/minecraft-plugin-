package dev.orderhub.flow;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.item.ItemCodec;
import dev.orderhub.item.ItemCodecException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Inventarhilfen. Nur im Entity-Kontext des jeweiligen Spielers aufrufen. */
public final class ItemService {

  private ItemService() {}

  /** Wie viele Stueck von {@code item} noch in den Hauptinventarteil (36 Slots) passen. */
  public static int capacityFor(Player player, ItemStack item) {
    PlayerInventory inv = player.getInventory();
    int max = Math.max(1, item.getMaxStackSize());
    long free = 0;
    ItemStack[] storage =
        java.util.Objects.requireNonNullElse(inv.getStorageContents(), new ItemStack[0]);
    for (ItemStack slot : storage) {
      if (slot == null || slot.getType() == Material.AIR || slot.isEmpty()) {
        free += max;
      } else if (slot.isSimilar(item)) {
        free += Math.max(0, max - slot.getAmount());
      }
    }
    return (int) Math.min(Integer.MAX_VALUE, free);
  }

  /**
   * Legt {@code amount} Stueck von {@code item} ins Inventar. Liefert die Menge, die tatsaechlich
   * hineinpasste.
   */
  public static int give(Player player, ItemStack item, int amount) {
    int remaining = amount;
    int max = Math.max(1, item.getMaxStackSize());
    while (remaining > 0) {
      ItemStack part = item.clone();
      part.setAmount(Math.min(max, remaining));
      var leftover = player.getInventory().addItem(part);
      if (!leftover.isEmpty()) {
        int notAdded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        return amount - remaining + (part.getAmount() - notAdded);
      }
      remaining -= part.getAmount();
    }
    return amount;
  }

  /** Dekodiert einen Snapshot, oder leer bei beschaedigten Daten. */
  public static Optional<ItemStack> decode(ItemCodec codec, ItemSnapshot snapshot) {
    try {
      return Optional.of(codec.decode(snapshot));
    } catch (ItemCodecException e) {
      return Optional.empty();
    }
  }

  /** Teilt eine Menge in Stacks bis zur maximalen Stackgroesse (nur fuer Anzeige und Rueckgabe). */
  public static List<ItemStack> stacks(ItemStack template, int amount) {
    List<ItemStack> out = new ArrayList<>();
    int max = Math.max(1, template.getMaxStackSize());
    int left = amount;
    while (left > 0) {
      ItemStack part = template.clone();
      part.setAmount(Math.min(max, left));
      out.add(part);
      left -= part.getAmount();
    }
    return out;
  }
}
