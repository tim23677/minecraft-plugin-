package dev.orderhub.item;

/**
 * Toleranz eines Auftrags beim Abgleich. Die Optionen {@code items.allow-*} bestimmen nur, was in
 * einem Auftrag vorkommen darf. Wie genau geliefert werden muss, legt der Auftrag fest, und zwar
 * mit Untergrenzen statt mit Ignorieren.
 *
 * @param minDurabilityPercent geforderte Restlebensdauer in Prozent (0 bis 100). 100 bedeutet:
 *     Schaden muss exakt dem der Vorlage entsprechen.
 * @param enchantmentsAtLeast {@code true}: jede geforderte Verzauberung muss mindestens in der
 *     geforderten Stufe vorhanden sein, zusaetzliche Verzauberungen sind erlaubt. {@code false}:
 *     Verzauberungen muessen exakt gleich sein.
 * @since 2.0.0
 */
public record Tolerance(int minDurabilityPercent, boolean enchantmentsAtLeast) {

  /** Keine Toleranz: alle Komponenten exakt gleich. */
  public static final Tolerance EXACT = new Tolerance(100, false);

  public Tolerance {
    if (minDurabilityPercent < 0 || minDurabilityPercent > 100) {
      throw new IllegalArgumentException("minDurabilityPercent must be 0..100");
    }
  }
}
