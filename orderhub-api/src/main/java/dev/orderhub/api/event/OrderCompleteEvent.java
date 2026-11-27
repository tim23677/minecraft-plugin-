package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Ein Auftrag wurde durch die letzte Lieferung vollstaendig erfuellt.
 *
 * @since 1.0.0
 */
public final class OrderCompleteEvent extends OrderEvent {
  private final UUID owner;
  private final String itemKey;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @param owner Besitzer des Auftrags
   * @param itemKey Material-Key des Items
   * @since 1.0.0
   */
  public OrderCompleteEvent(long orderId, UUID owner, String itemKey) {
    super(orderId);
    this.owner = owner;
    this.itemKey = itemKey;
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
}
