package dev.orderhub.api.event;

/** Ein Auftrag ist abgelaufen, der Restbetrag wird erstattet. */
public final class OrderExpireEvent extends OrderEvent {
  public OrderExpireEvent(long orderId) {
    super(orderId);
  }
}
