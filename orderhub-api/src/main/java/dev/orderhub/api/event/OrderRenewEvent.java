package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Der Besitzer hat die automatische Verlaengerung eines Auftrags ein- oder ausgeschaltet.
 *
 * @since 1.2.0
 */
public final class OrderRenewEvent extends OrderEvent {
  private final UUID owner;
  private final boolean autoRenew;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags
   * @param owner Besitzer des Auftrags
   * @param autoRenew der neue Zustand der automatischen Verlaengerung
   * @since 1.2.0
   */
  public OrderRenewEvent(long orderId, UUID owner, boolean autoRenew) {
    super(orderId);
    this.owner = owner;
    this.autoRenew = autoRenew;
  }

  /**
   * Besitzer des Auftrags.
   *
   * @return der Besitzer
   * @since 1.2.0
   */
  public UUID owner() {
    return owner;
  }

  /**
   * Neuer Zustand der automatischen Verlaengerung.
   *
   * @return {@code true}, wenn der Auftrag sich nun selbst verlaengert
   * @since 1.2.0
   */
  public boolean autoRenew() {
    return autoRenew;
  }
}
