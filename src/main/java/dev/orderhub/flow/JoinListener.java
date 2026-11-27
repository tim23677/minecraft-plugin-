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

  /** Zeigt offene Benachrichtigungen gesammelt: wenige einzeln, viele als Zusammenfassung. */
  private void notes(org.bukkit.entity.Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().takeNotes(player.getUniqueId(), 50),
        notes -> {
          if (notes.isEmpty()) {
            return;
          }
          if (notes.size() > 3) {
            player.sendMessage(
                s.messages().prefixed("notify.summary", Messages.number("count", notes.size())));
            return;
          }
          for (var note : notes) {
            String[] parts = note.payload().split("\\|");
            if (note.kind().equals("price-alert") && parts.length == 4) {
              player.sendMessage(
                  s.messages()
                      .prefixed(
                          "notify.price-alert",
                          Messages.text("item", parts[0]),
                          Messages.text("quantity", parts[1]),
                          Messages.text("unit", parts[2]),
                          Messages.text("id", parts[3])));
            }
          }
        },
        orphan -> {});
  }
}
