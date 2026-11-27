package dev.orderhub.api.event;

/** Ein Auftrag wurde storniert, der Restbetrag wird erstattet. */
public final class OrderCancelEvent extends OrderEvent {
  private final java.util.UUID owner;
  private final long refundedMinor;

  public OrderCancelEvent(long orderId, java.util.UUID owner, long refundedMinor) {
    super(orderId);
    this.owner = owner;
    this.refundedMinor = refundedMinor;
  }

  public java.util.UUID owner() {
    return owner;
  }

  public long refundedMinor() {
    return refundedMinor;
  }
}
