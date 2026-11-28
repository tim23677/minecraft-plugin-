package dev.orderhub.api.event;

import java.util.UUID;

/**
 * Ein Handelspaar ist auffaellig: derselbe Lieferant hat innerhalb von 24 Stunden die konfigurierte
 * Zahl von Lieferungen an Auftraege desselben Auftraggebers erreicht (siehe {@code
 * anti-exploit.pair-trades-per-day}). Das Ereignis wird einmal beim Erreichen der Schwelle
 * ausgeloest. OrderHub blockiert nichts, andere Plugins koennen reagieren.
 *
 * @since 1.2.0
 */
public final class SuspiciousTradeEvent extends OrderEvent {
  private final UUID supplier;
  private final UUID owner;
  private final int count;

  /**
   * Legt das Ereignis an.
   *
   * @param orderId Id des Auftrags der Lieferung, die die Schwelle erreicht hat
   * @param supplier der liefernde Spieler
   * @param owner der Auftraggeber
   * @param count Zahl der Lieferungen des Paares in 24 Stunden
   * @since 1.2.0
   */
  public SuspiciousTradeEvent(long orderId, UUID supplier, UUID owner, int count) {
    super(orderId);
    this.supplier = supplier;
    this.owner = owner;
    this.count = count;
  }

  /**
   * Der liefernde Spieler.
   *
   * @return der Lieferant
   * @since 1.2.0
   */
  public UUID supplier() {
    return supplier;
  }

  /**
   * Der Auftraggeber.
   *
   * @return der Besitzer der Auftraege
   * @since 1.2.0
   */
  public UUID owner() {
    return owner;
  }

  /**
   * Zahl der Lieferungen des Paares in 24 Stunden.
   *
   * @return die Anzahl
   * @since 1.2.0
   */
  public int count() {
    return count;
  }
}
