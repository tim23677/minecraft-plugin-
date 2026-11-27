package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.item.PreparedTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /order sell [menge]}: sucht fuer das Item in der Hand den passenden Auftrag mit dem
 * hoechsten Stueckpreis und oeffnet das Liefermenue mit den Items als Vorschau. Geliefert wird erst
 * nach Bestaetigen, dort gelten unveraendert Journal, Reservierung und Wiederherstellung.
 *
 * @since 2.0.0
 */
public final class SellFlow {

  private static final int CANDIDATES = 50;

  private record Candidate(Order order, OrderOptions options) {}

  private final Services s;
  private final DeliveryFlow delivery;

  public SellFlow(Services services, DeliveryFlow delivery) {
    this.s = services;
    this.delivery = delivery;
  }

  /**
   * @param amountArg Menge als Text oder {@code null} fuer alles, was in der Hand liegt
   */
  public void start(Player player, String amountArg) {
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand.isEmpty()) {
      player.sendMessage(s.messages().prefixed("sell.no-item"));
      return;
    }
    OptionalInt wanted = parseAmount(amountArg, hand.getAmount());
    if (wanted.isEmpty()) {
      player.sendMessage(
          s.messages().prefixed("general.invalid-amount", Messages.text("input", amountArg)));
      return;
    }
    String itemKey = hand.getType().getKey().toString();
    ItemStack sample = hand.clone();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage()
                .transact(uow -> {
                  List<Candidate> out = new ArrayList<>();
                  for (Order order :
                      uow.orders()
                          .search(
                              new OrderQuery(
                                  null,
                                  itemKey,
                                  null,
                                  OrderStatus.ACTIVE,
                                  OrderQuery.Sort.HIGHEST_PRICE,
                                  0,
                                  CANDIDATES))) {
                    out.add(new Candidate(order, uow.orders().options(order.id())));
                  }
                  return out;
                })
                .join(),
        candidates -> choose(player, sample, wanted.getAsInt(), candidates),
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  /** Entity-Kontext: waehlt den besten passenden Auftrag und oeffnet die Vorschau. */
  private void choose(Player player, ItemStack sample, int wanted, List<Candidate> candidates) {
    // Die Hand kann sich seit dem Befehl geaendert haben: gleiche Art, sonst abbrechen.
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand.isEmpty() || hand.getType() != sample.getType()) {
      player.sendMessage(s.messages().prefixed("sell.hand-changed"));
      return;
    }
    boolean anyOpen = !candidates.isEmpty();
    for (Candidate c : candidates) {
      Order order = c.order();
      if (order.owner().equals(player.getUniqueId()) || order.remaining() <= 0) {
        continue;
      }
      Optional<PreparedTemplate> tpl = s.matcher().prepare(order.item());
      if (tpl.isEmpty()
          || !s.matcher().matches(hand, tpl.get(), DeliveryFlow.toleranceOf(c.options()))) {
        continue;
      }
      int available = countMatching(player, tpl.get(), DeliveryFlow.toleranceOf(c.options()));
      int quantity = Math.min(Math.min(wanted, available), order.remaining());
      if (quantity < order.minDelivery()) {
        continue;
      }
      delivery.openPrefilled(player, order, c.options(), quantity);
      return;
    }
    player.sendMessage(s.messages().prefixed(anyOpen ? "sell.none-fits" : "sell.no-order"));
  }

  private int countMatching(
      Player player, PreparedTemplate tpl, dev.orderhub.item.Tolerance tolerance) {
    int total = 0;
    for (ItemStack stack :
        java.util.Objects.requireNonNull(player.getInventory().getStorageContents())) {
      if (stack != null && !stack.isEmpty() && s.matcher().matches(stack, tpl, tolerance)) {
        total += stack.getAmount();
      }
    }
    return total;
  }

  static OptionalInt parseAmount(String raw, int handAmount) {
    if (raw == null) {
      return OptionalInt.of(handAmount);
    }
    if (!raw.matches("[0-9]{1,7}")) {
      return OptionalInt.empty();
    }
    int value = Integer.parseInt(raw);
    return value < 1 ? OptionalInt.empty() : OptionalInt.of(value);
  }
}
