package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Beim Login: offene Rueckgaben automatisch uebergeben, auf abholbare Items hinweisen. */
public final class JoinListener implements Listener {

  private final Services s;
  private final ParcelFlow returns;
  private final ParcelFlow claims;

  public JoinListener(Services services, ParcelFlow returns, ParcelFlow claims) {
    this.s = services;
    this.returns = returns;
    this.claims = claims;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    var player = event.getPlayer();
    s.scheduler()
        .tryRunOn(
            player,
            () -> {
              returns.claimAll(player);
              notes(player);
              claims.list(
                  player,
                  0,
                  50,
                  parcels -> {
                    if (!parcels.isEmpty()) {
                      player.sendMessage(
                          s.messages()
                              .prefixed(
                                  "notify.claims-waiting",
                                  Messages.number("count", parcels.size())));
                    }
                  });
            });
  }

  /** Zeigt offene Hinweise seit dem letzten Besuch als eine Zusammenfassung. */
  private void notes(org.bukkit.entity.Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().takeNotes(player.getUniqueId(), 200),
        notes -> {
          LoginSummary summary = LoginSummary.of(notes);
          if (summary.isEmpty()) {
            return;
          }
          var settings = s.settings().get();
          Notifier.Display display = Notifier.Display.parse(settings.notificationDisplay());
          String sound = settings.notificationSound();
          if (summary.deliveries() > 0 || summary.expiredOrders() > 0) {
            Notifier.show(
                player,
                s.messages()
                    .prefixed(
                        "notify.login-summary",
                        Messages.number("items", summary.deliveredItems()),
                        Messages.number("deliveries", summary.deliveries()),
                        Messages.number("expired", summary.expiredOrders())),
                display,
                sound);
          }
          if (summary.alerts().size() > LoginSummary.MAX_ALERTS_SHOWN) {
            Notifier.show(
                player,
                s.messages()
                    .prefixed("notify.summary", Messages.number("count", summary.alerts().size())),
                display,
                "");
            return;
          }
          for (LoginSummary.Alert alert : summary.alerts()) {
            Notifier.show(
                player,
                s.messages()
                    .prefixed(
                        "notify.price-alert",
                        Messages.text("item", alert.item()),
                        Messages.text("quantity", alert.quantity()),
                        Messages.text("unit", alert.unit()),
                        Messages.text("id", alert.orderId())),
                display,
                summary.deliveries() == 0 && summary.expiredOrders() == 0 ? sound : "");
          }
        },
        orphan -> {});
  }
}
