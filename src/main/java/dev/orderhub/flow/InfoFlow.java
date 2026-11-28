package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.money.Money;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /order info <id>}: zeigt Menge, Preis, Status, Restlaufzeit und Optionen eines Auftrags im
 * Chat. Aufraege sind oeffentlich, jeder mit Marktzugriff darf sie ansehen.
 *
 * @since 2.0.0
 */
public final class InfoFlow {

  private final Services s;
  private final LongSupplier clock;

  public InfoFlow(Services services) {
    this(services, System::currentTimeMillis);
  }

  InfoFlow(Services services, LongSupplier clock) {
    this.s = services;
    this.clock = clock;
  }

  public void show(Player player, long orderId) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage()
                .transact(uow -> {
                  Optional<Order> order = uow.orders().find(orderId);
                  return order.map(o -> new Found(o, uow.orders().options(o.id())));
                })
                .join(),
        found -> {
          if (found.isEmpty()) {
            player.sendMessage(
                s.messages().prefixed("order.not-found", Messages.number("id", orderId)));
            return;
          }
          player.sendMessage(render(found.get()));
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private record Found(Order order, OrderOptions options) {}

  private net.kyori.adventure.text.Component render(Found f) {
    Order o = f.order();
    Optional<ItemStack> stack = ItemService.decode(s.codec(), o.item());
    var name =
        stack.isPresent()
            ? ItemService.nameOf(stack.get())
            : net.kyori.adventure.text.Component.text(o.item().itemKey());
    long hours = Math.max(0, (o.expiresAt() - clock.getAsLong()) / 3_600_000L);
    String options =
        OptionFlags.describe(f.options(), key -> s.messages().raw(OptionFlags.messageKey(key)));
    return s.messages()
        .prefixed(
            "order.info",
            Messages.number("id", o.id()),
            Messages.number("filled", o.quantityFilled()),
            Messages.number("total", o.quantityTotal()),
            Messages.component("item", name),
            Messages.text("unit", Money.format(o.unitPrice())),
            Messages.text("status", o.status().name().toLowerCase(Locale.ROOT)),
            Messages.number("hours", hours),
            Messages.text("options", options.isEmpty() ? "-" : options));
  }
}
