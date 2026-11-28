package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Ein Auftrag wurde erstellt und der Escrow reserviert.
 *
 * @since 1.0.0
 */
public final class OrderCreateEvent extends OrderEvent {
  private final UUID owner;
  private final String itemKey;
  private final int quantity;
  private final long unitPriceMinor;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @param owner Besitzer des Auftrags
   * @param itemKey Material-Key des Items
   * @param quantity bestellte Menge
   * @param unitPriceMinor Stueckpreis in Minor Units
   * @since 1.0.0
   */
  public OrderCreateEvent(
      long orderId, UUID owner, String itemKey, int quantity, long unitPriceMinor) {
    super(orderId);
    this.owner = owner;
    this.itemKey = itemKey;
    this.quantity = quantity;
    this.unitPriceMinor = unitPriceMinor;
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
   * Bestellte Menge.
   *
   * @return die Menge
   * @since 1.0.0
   */
  public int quantity() {
    return quantity;
  }

  /**
   * Stueckpreis.
   *
   * @return Minor Units
   * @since 1.0.0
   */
  public long unitPriceMinor() {
    return unitPriceMinor;
  }
}
