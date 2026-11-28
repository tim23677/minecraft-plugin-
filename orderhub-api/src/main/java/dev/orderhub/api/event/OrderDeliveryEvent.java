package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Eine Lieferung wurde festgeschrieben. Die Auszahlung folgt asynchron.
 *
 * @since 1.0.0
 */
public final class OrderDeliveryEvent extends OrderEvent {
  private final UUID supplier;
  private final UUID owner;
  private final String itemKey;
  private final int quantity;
  private final long grossMinor;
  private final long netPayoutMinor;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @param supplier Lieferant
   * @param owner Besitzer des Auftrags
   * @param itemKey Material-Key des Items
   * @param quantity gelieferte Menge
   * @param grossMinor Bruttobetrag in Minor Units
   * @param netPayoutMinor Auszahlung an den Lieferanten nach Steuer in Minor Units
   * @since 1.0.0
   */
  public OrderDeliveryEvent(
      long orderId,
      UUID supplier,
      UUID owner,
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

  /**
   * Lieferant.
   *
   * @return der Lieferant
   * @since 1.0.0
   */
  public UUID supplier() {
    return supplier;
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
   * Item des Auftrags.
   *
   * @return Material-Key
   * @since 1.0.0
   */
  public String itemKey() {
    return itemKey;
  }

  /**
   * Gelieferte Menge.
   *
   * @return die Menge
   * @since 1.0.0
   */
  public int quantity() {
    return quantity;
  }

  /**
   * Bruttobetrag der Lieferung.
   *
   * @return Minor Units
   * @since 1.0.0
   */
  public long grossMinor() {
    return grossMinor;
  }

  /**
   * Auszahlung an den Lieferanten nach Steuer.
   *
   * @return Minor Units
   * @since 1.0.0
   */
  public long netPayoutMinor() {
    return netPayoutMinor;
  }
}
