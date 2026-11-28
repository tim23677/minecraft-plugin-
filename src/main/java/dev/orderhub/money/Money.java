package dev.orderhub.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Geldbetraege als {@code long} in Minor Units (Praezision 2: 1,00 = 100). {@code double} gibt es
 * nur an der Vault-Grenze ({@link #fromDouble}, {@link #toDouble}).
 */
public final class Money {

  /** Nachkommastellen der Waehrung. */
  public static final int SCALE = 2;

  /** Faktor zwischen Major und Minor Units. */
  public static final long FACTOR = 100L;

  /** Obergrenze, bleibt unter 2^53, damit double ganze Minor Units exakt darstellt. */
  public static final long MAX = 9_000_000_000_000_00L;

  /** Rundung bei jeder Umrechnung aus Dezimalwerten. */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

  private Money() {}

  /** Wandelt einen Dezimalbetrag in Minor Units. Wirft bei Ueberlauf oder negativem Wert. */
  public static long fromDecimal(BigDecimal amount) {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("negative amount");
    }
    BigDecimal minor = amount.movePointRight(SCALE).setScale(0, ROUNDING);
    if (minor.compareTo(BigDecimal.valueOf(MAX)) > 0) {
      throw new ArithmeticException("amount exceeds maximum");
    }
    return minor.longValueExact();
  }

  /** Vault-Grenze: double rein. Lehnt NaN, Infinity, negative Werte und Ueberlauf ab. */
  public static long fromDouble(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("not a finite amount");
    }
    return fromDecimal(BigDecimal.valueOf(value));
  }

  /** Vault-Grenze: double raus. Exakt, solange {@code minor <= MAX}. */
  public static double toDouble(long minor) {
    requireValid(minor);
    return BigDecimal.valueOf(minor, SCALE).doubleValue();
  }

  /** Stueckpreis mal Menge mit Ueberlaufpruefung und Obergrenze. */
  public static long multiply(long unitPrice, int quantity) {
    if (unitPrice < 0 || quantity < 0) {
      throw new IllegalArgumentException("negative operand");
    }
    long total = Math.multiplyExact(unitPrice, (long) quantity);
    requireValid(total);
    return total;
  }

  /** Addition mit Ueberlaufpruefung und Obergrenze. */
  public static long add(long a, long b) {
    long sum = Math.addExact(a, b);
    requireValid(sum);
    return sum;
  }

  /** Anteil in Basispunkten (100 = 1 %), gerundet mit {@link #ROUNDING}. */
  public static long percentOf(long amount, int basisPoints) {
    requireValid(amount);
    if (basisPoints < 0 || basisPoints > 10_000) {
      throw new IllegalArgumentException("basis points out of range");
    }
    return BigDecimal.valueOf(amount)
        .multiply(BigDecimal.valueOf(basisPoints))
        .divide(BigDecimal.valueOf(10_000), 0, ROUNDING)
        .longValueExact();
  }

  /** Menschenlesbar, z. B. {@code 1234.50}, ohne Waehrungssymbol. */
  public static String format(long minor) {
    return BigDecimal.valueOf(minor, SCALE).toPlainString();
  }

  /** Strenges Parsen von Spielereingaben wie {@code 12}, {@code 12.5}, {@code 1.5k}, {@code 2m}. */
  public static long parse(String input) {
    if (input == null) {
      throw new NumberFormatException("empty");
    }
    String s = input.trim().toLowerCase(java.util.Locale.ROOT);
    long multiplier = 1;
    if (!s.isEmpty()) {
      char last = s.charAt(s.length() - 1);
      if (last == 'k') {
        multiplier = 1_000L;
      } else if (last == 'm') {
        multiplier = 1_000_000L;
      } else if (last == 'b') {
        multiplier = 1_000_000_000L;
      }
      if (multiplier != 1) {
        s = s.substring(0, s.length() - 1);
      }
    }
    if (!s.matches("[0-9]{1,15}(\\.[0-9]{1,2})?")) {
      throw new NumberFormatException("invalid amount: " + input);
    }
    try {
      return fromDecimal(new BigDecimal(s).multiply(BigDecimal.valueOf(multiplier)));
    } catch (ArithmeticException e) {
      throw new NumberFormatException("amount too large: " + input);
    }
  }

  /** Prueft, dass ein Betrag im gueltigen Bereich {@code 0..MAX} liegt. */
  public static void requireValid(long minor) {
    if (minor < 0 || minor > MAX) {
      throw new ArithmeticException("amount out of range: " + minor);
    }
  }
}
