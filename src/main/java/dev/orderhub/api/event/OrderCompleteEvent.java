package dev.orderhub.api.event;

/** Ein Auftrag wurde durch die letzte Lieferung vollstaendig erfuellt. */
public final class OrderCompleteEvent extends OrderEvent {
  private final java.util.UUID owner;
  private final String itemKey;

  public OrderCompleteEvent(long orderId, java.util.UUID owner, String itemKey) {
    super(orderId);
    this.owner = owner;
    this.itemKey = itemKey;
  }

  public java.util.UUID owner() {
    return owner;
  }

  public String itemKey() {
    return itemKey;
  }
}
