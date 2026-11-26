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
}
