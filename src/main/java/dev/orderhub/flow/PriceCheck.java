package dev.orderhub.flow;

/**
 * Prueft, ob ein Stueckpreis weit vom Marktmittel abweicht. Rein rechnerisch.
 *
 * @since 2.0.0
 */
public final class PriceCheck {

  /** Richtung der Abweichung. */
  public enum Deviation {
    NONE,
    TOO_HIGH,
    TOO_LOW
  }

  private PriceCheck() {}

  /**
   * @param price angebotener Stueckpreis
   * @param reference Mittel (Median) der letzten Handelsgeschaefte, 0 oder weniger = unbekannt
   * @param factor Faktor, 0 oder weniger schaltet die Pruefung aus
   */
  public static Deviation check(long price, long reference, int factor) {
    if (factor <= 0 || reference <= 0 || price <= 0) {
      return Deviation.NONE;
    }
    if (price > safeMultiply(reference, factor)) {
      return Deviation.TOO_HIGH;
    }
    if (safeMultiply(price, factor) < reference) {
      return Deviation.TOO_LOW;
    }
    return Deviation.NONE;
  }

  private static long safeMultiply(long a, long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }
}
