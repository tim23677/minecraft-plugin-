package dev.orderhub.market;

/**
 * Ein tatsaechlich abgeschlossenes Handelsgeschaeft (Lieferung). Offene oder stornierte Auftraege sind
 * kein Trade und duerfen hier nie auftauchen.
 */
public record Trade(long unitPrice, int quantity, long at) {
  public Trade {
    if (unitPrice < 0 || quantity < 1) {
      throw new IllegalArgumentException("invalid trade");
    }
  }
}
