package dev.orderhub.domain;

/**
 * Optionen eines Auftrags neben den Kerndaten: wie genau geliefert werden muss und ob gelieferte
 * Items automatisch abgeholt werden. Ohne gespeicherte Optionen gilt {@link #EXACT}.
 *
 * @param minDurabilityPercent geforderte Restlebensdauer, 100 = exakt gleicher Schaden
 * @param enchantmentsAtLeast Mindeststufen statt exakter Verzauberungen
 * @param autoClaim gelieferte Items automatisch in eine freie Inventarluecke legen
 * @since 2.0.0
 */
public record OrderOptions(
    int minDurabilityPercent, boolean enchantmentsAtLeast, boolean autoClaim) {

  public static final OrderOptions EXACT = new OrderOptions(100, false, false);

  public OrderOptions {
    if (minDurabilityPercent < 0 || minDurabilityPercent > 100) {
      throw new IllegalArgumentException("minDurabilityPercent must be 0..100");
    }
  }

  /** {@code true}, wenn der Abgleich exakt ist (keine Toleranz). */
  public boolean exact() {
    return minDurabilityPercent == 100 && !enchantmentsAtLeast;
  }
}
