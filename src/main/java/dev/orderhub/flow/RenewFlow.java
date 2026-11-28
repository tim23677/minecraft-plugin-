package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import org.bukkit.entity.Player;

/**
 * {@code /order renew <id> on|off}: schaltet die Auto-Verlaengerung eines eigenen Auftrags.
 *
 * @since 2.0.0
 */
public final class RenewFlow {

  private final Services s;

  public RenewFlow(Services services) {
    this.s = services;
  }

  /**
   * @param args alle Argumente des Befehls inklusive {@code renew}
   */
  public void handle(Player player, String[] args) {
    boolean on = args.length == 3 && args[2].equalsIgnoreCase("on");
    boolean off = args.length == 3 && args[2].equalsIgnoreCase("off");
    if (!(on || off) || !args[1].matches("[0-9]{1,18}")) {
      player.sendMessage(s.messages().prefixed("renew.usage"));
      return;
    }
    long id = Long.parseLong(args[1]);
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.orders().changeAutoRenew(id, player.getUniqueId(), on),
        r -> {
          if (r.status() == dev.orderhub.service.OrderService.ActionStatus.OK) {
            // Asynchrone Ereignisse duerfen nicht auf dem Haupt-Thread ausgeloest werden.
            s.scheduler()
                .runAsync(() ->
                    dev.orderhub.integration.Events.fire(
                        new dev.orderhub.api.event.OrderRenewEvent(id, player.getUniqueId(), on)));
          }
          player.sendMessage(
              switch (r.status()) {
                case OK ->
                    s.messages().prefixed(on ? "renew.on" : "renew.off", Messages.number("id", id));
                case ALREADY_DONE ->
                    s.messages().prefixed("renew.unchanged", Messages.number("id", id));
                case NOT_FOUND ->
                    s.messages().prefixed("order.not-found", Messages.number("id", id));
                case NOT_OWNER -> s.messages().prefixed("order.not-yours");
                default -> s.messages().prefixed("order.not-active");
              });
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }
}
