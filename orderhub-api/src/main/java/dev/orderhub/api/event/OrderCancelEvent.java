package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Ein Auftrag wurde storniert, der Restbetrag wird erstattet.
 *
 * @since 1.0.0
 */
public final class OrderCancelEvent extends OrderEvent {
  private final UUID owner;
  private final long refundedMinor;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @param owner Besitzer des Auftrags
   * @param refundedMinor erstatteter Betrag in Minor Units
   * @since 1.0.0
   */
  public OrderCancelEvent(long orderId, UUID owner, long refundedMinor) {
    super(orderId);
    this.owner = owner;
    this.refundedMinor = refundedMinor;
  }

  /**
   * Besitzer des Auftrags.
   *
   * @return der Besitzer
   * @since 1.0.0
   */
  public UUID owner() {
    return owner;
  }

  /**
   * Erstatteter Betrag.
   *
   * @return Minor Units
   * @since 1.0.0
   */
  public long refundedMinor() {
    return refundedMinor;
  }
}
