package dev.orderhub.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Basis aller OrderHub-Ereignisse. Sie sind asynchron (nicht auf dem Server-Thread) und rein
 * informativ: Sie lassen sich nicht abbrechen, weil Geld und Items zu diesem Zeitpunkt bereits
 * bewegt sind. Handler duerfen keine Bukkit-Welt- oder Entity-Daten anfassen, ohne vorher auf den
 * zustaendigen Scheduler zu wechseln.
 */
public abstract class OrderEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final long orderId;

  protected OrderEvent(long orderId) {
    super(true);
    this.orderId = orderId;
  }

  /** Id des betroffenen Auftrags. */
  public final long orderId() {
    return orderId;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
