package dev.orderhub.api.event;

/** Ein Auftrag wurde erstellt und der Escrow reserviert. */
public final class OrderCreateEvent extends OrderEvent {
  private final java.util.UUID owner;
  private final String itemKey;
  private final int quantity;
  private final long unitPriceMinor;

  public OrderCreateEvent(
      long orderId, java.util.UUID owner, String itemKey, int quantity, long unitPriceMinor) {
    super(orderId);
    this.owner = owner;
    this.itemKey = itemKey;
    this.quantity = quantity;
    this.unitPriceMinor = unitPriceMinor;
  }

  public java.util.UUID owner() {
    return owner;
  }

  public String itemKey() {
    return itemKey;
  }

  public int quantity() {
    return quantity;
  }

  public long unitPriceMinor() {
    return unitPriceMinor;
  }
}
