package dev.orderhub.api.event;

/**
 * Ein Auftrag ist abgelaufen, der Restbetrag wird erstattet.
 *
 * @since 1.0.0
 */
public final class OrderExpireEvent extends OrderEvent {

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @since 1.0.0
   */
  public OrderExpireEvent(long orderId) {
    super(orderId);
  }
}
