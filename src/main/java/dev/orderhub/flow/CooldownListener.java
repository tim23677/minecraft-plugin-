package dev.orderhub.flow;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Raeumt den Erstell-Cooldown eines Spielers auf, wenn er den Server verlaesst. */
public final class CooldownListener implements Listener {

  private final CreateFlow create;

  public CooldownListener(CreateFlow create) {
    this.create = create;
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    create.forget(event.getPlayer().getUniqueId());
  }
}
