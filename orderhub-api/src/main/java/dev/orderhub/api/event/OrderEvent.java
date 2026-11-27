package dev.orderhub.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Basis aller OrderHub-Ereignisse. Sie sind asynchron (nicht auf dem Server-Thread) und rein
 * informativ: Sie lassen sich nicht abbrechen, weil Geld und Items zu diesem Zeitpunkt bereits
 * bewegt sind. Handler duerfen keine Bukkit-Welt- oder Entity-Daten anfassen, ohne vorher auf den
 * zustaendigen Scheduler zu wechseln.
 *
 * @since 1.0.0
 */
public abstract class OrderEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final long orderId;

  /**
   * Legt ein asynchrones Ereignis an.
   *
   * @param orderId Id des betroffenen Auftrags
   * @since 1.0.0
   */
  protected OrderEvent(long orderId) {
    super(true);
    this.orderId = orderId;
  }

  /**
   * Id des betroffenen Auftrags.
   *
   * @return die Auftrags-Id
   * @since 1.0.0
   */
  public final long orderId() {
    return orderId;
  }

  /**
   * Liste der Handler (Bukkit-Vorgabe).
   *
   * @return die Handler-Liste
   * @since 1.0.0
   */
  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  /**
   * Liste der Handler (Bukkit-Vorgabe fuer statische Registrierung).
   *
   * @return die Handler-Liste
   * @since 1.0.0
   */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
