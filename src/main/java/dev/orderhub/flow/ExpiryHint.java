package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;

/**
 * Beim Login: weist darauf hin, dass offene Auftraege bald ablaufen. Der Spieler kann sie dann mit
 * {@code /order renew <id> on} verlaengern lassen oder neu anlegen. Nur lesend.
 *
 * @since 2.0.0
 */
public final class ExpiryHint {

  private final Services s;
  private final LongSupplier clock;

  public ExpiryHint(Services services) {
    this(services, System::currentTimeMillis);
  }

  ExpiryHint(Services services, LongSupplier clock) {
    this.s = services;
    this.clock = clock;
  }

  /** Zaehlt asynchron und meldet im Entity-Kontext, wenn Auftraege bald ablaufen. */
  public void check(Player player) {
    int hours = s.settings().get().expiryWarningHours();
    if (hours <= 0) {
      return;
    }
    long now = clock.getAsLong();
    long until = now + hours * 3_600_000L;
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage()
                .transact(
                    uow -> uow.orders().countExpiringBetween(player.getUniqueId(), now, until))
                .join(),
        count -> {
          if (count > 0) {
            player.sendMessage(
                s.messages()
                    .prefixed(
                        "notify.expiring-soon",
                        Messages.number("count", count),
                        Messages.number("hours", hours)));
          }
        },
        orphan -> {});
  }
}
