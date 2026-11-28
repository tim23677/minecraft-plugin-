package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.tx.DeliveryCommitted;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Erkennt auffaellige Handelspaare: liefert derselbe Spieler innerhalb von 24 Stunden auffaellig
 * oft an Auftraege desselben Auftraggebers, bekommen Administratoren einen Hinweis, und der Fall
 * steht im Audit-Protokoll. Es wird nichts blockiert, die Meldung kommt genau einmal, wenn die
 * Schwelle erreicht wird.
 *
 * @since 2.0.0
 */
public final class PairWatch {

  private static final long DAY_MILLIS = 24L * 3_600_000L;

  private final Services s;

  public PairWatch(Services services) {
    this.s = services;
  }

  /**
   * Prueft nach einer festgeschriebenen Lieferung. Blockiert (Datenbank), nie auf einem
   * Minecraft-Thread aufrufen.
   *
   * @return die Anzahl der Lieferungen des Paares in 24 Stunden, 0 wenn die Pruefung aus ist
   */
  public int check(DeliveryCommitted event) {
    int threshold = s.settings().get().pairTradesPerDay();
    if (threshold <= 0 || event.supplier().equals(event.owner())) {
      return 0;
    }
    int count;
    try {
      count =
          s.storage()
              .transact(uow ->
                  uow.journal()
                      .countDeliveriesBetween(
                          event.supplier(), event.owner(), uow.now() - DAY_MILLIS))
              .join();
    } catch (RuntimeException e) {
      s.logger().log(Level.WARNING, "pair check failed", e);
      return 0;
    }
    if (count != threshold) {
      return count;
    }
    String detail = event.supplier() + " -> " + event.owner() + " x" + count + " in 24h";
    try {
      s.storage()
          .transact(uow -> {
            uow.audit()
                .append(
                    new dev.orderhub.storage.AuditRepository.Entry(
                        "system",
                        "suspicious.pair",
                        event.supplier().toString(),
                        "anti-exploit",
                        detail,
                        s.storage().serverId(),
                        uow.now()));
            return null;
          })
          .join();
    } catch (RuntimeException e) {
      s.logger().log(Level.WARNING, "pair audit failed", e);
    }
    dev.orderhub.integration.Events.fire(
        new dev.orderhub.api.event.SuspiciousTradeEvent(
            event.orderId(), event.supplier(), event.owner(), count));
    notifyAdmins(event, count);
    return count;
  }

  private void notifyAdmins(DeliveryCommitted event, int count) {
    String supplier = name(event.supplier());
    String owner = name(event.owner());
    for (Player admin : Bukkit.getOnlinePlayers()) {
      if (admin.hasPermission("orderhub.admin.view")) {
        s.scheduler()
            .tryRunOn(
                admin,
                () ->
                    admin.sendMessage(
                        s.messages()
                            .get(
                                "admin.suspicious-pair",
                                Messages.text("supplier", supplier),
                                Messages.text("owner", owner),
                                Messages.number("count", count))));
      }
    }
  }

  private static String name(java.util.UUID id) {
    var known = Bukkit.getOfflinePlayerIfCached(id.toString());
    Player online = Bukkit.getPlayer(id);
    if (online != null) {
      return online.getName();
    }
    return known != null && known.getName() != null ? known.getName() : id.toString();
  }
}
