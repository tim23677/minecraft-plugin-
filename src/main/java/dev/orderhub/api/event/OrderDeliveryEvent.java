package dev.orderhub.api.event;

/** Eine Lieferung wurde festgeschrieben. Die Auszahlung folgt asynchron. */
public final class OrderDeliveryEvent extends OrderEvent {
  private final java.util.UUID supplier;
  private final java.util.UUID owner;
  private final String itemKey;
  private final int quantity;
  private final long grossMinor;
  private final long netPayoutMinor;

  public OrderDeliveryEvent(
      long orderId,
      java.util.UUID supplier,
      java.util.UUID owner,
      String itemKey,
      int quantity,
      long grossMinor,
      long netPayoutMinor) {
    super(orderId);
    this.supplier = supplier;
    this.owner = owner;
    this.itemKey = itemKey;
    this.quantity = quantity;
    this.grossMinor = grossMinor;
    this.netPayoutMinor = netPayoutMinor;
  }

  public java.util.UUID supplier() {
    return supplier;
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

  public long grossMinor() {
    return grossMinor;
  }

  public long netPayoutMinor() {
    return netPayoutMinor;
  }
}
