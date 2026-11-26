package dev.orderhub.market;

/** Offener Auftrag aus Sicht der Marktstatistik: Stueckpreis und noch offene Menge. */
public record OpenOrderView(long unitPrice, int remaining) {
  public OpenOrderView {
    if (unitPrice < 0 || remaining < 0) {
      throw new IllegalArgumentException("invalid open order");
    }
  }
}
