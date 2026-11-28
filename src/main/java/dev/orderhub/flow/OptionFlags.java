package dev.orderhub.flow;

import dev.orderhub.domain.OrderOptions;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Liest die Auftragsoptionen aus den Befehlsargumenten: {@code durability=60} (Restlebensdauer in
 * Prozent), {@code enchants} (Verzauberungen mindestens) und {@code autoclaim}.
 *
 * @since 2.0.0
 */
public final class OptionFlags {

  /** Ergebnis: die Optionen oder das erste nicht lesbare Argument. */
  public record Result(OrderOptions options, String invalid) {
    public boolean ok() {
      return invalid == null;
    }
  }

  /** Schluesselwoerter fuer die Tab-Vervollstaendigung. */
  public static final List<String> SUGGESTIONS = List.of("durability=", "enchants", "autoclaim");

  private OptionFlags() {}

  /** Meldungsschluessel der Kurzbeschreibung fuer {@link #describe}. */
  public static String messageKey(String option) {
    return switch (option) {
      case "durability" -> "create.option-durability";
      case "enchants" -> "create.option-enchants";
      default -> "create.option-autoclaim";
    };
  }

  public static Result parse(List<String> tokens) {
    int durability = 100;
    boolean enchants = false;
    boolean autoClaim = false;
    for (String raw : tokens) {
      String token = raw.toLowerCase(Locale.ROOT);
      if (token.equals("enchants")) {
        enchants = true;
      } else if (token.equals("autoclaim")) {
        autoClaim = true;
      } else if (token.startsWith("durability=") || token.startsWith("durability:")) {
        Optional<Integer> value = percent(token.substring("durability=".length()));
        if (value.isEmpty()) {
          return new Result(OrderOptions.EXACT, raw);
        }
        durability = value.get();
      } else {
        return new Result(OrderOptions.EXACT, raw);
      }
    }
    return new Result(new OrderOptions(durability, enchants, autoClaim), null);
  }

  private static Optional<Integer> percent(String text) {
    String digits = text.endsWith("%") ? text.substring(0, text.length() - 1) : text;
    if (!digits.matches("[0-9]{1,3}")) {
      return Optional.empty();
    }
    int value = Integer.parseInt(digits);
    return value > 100 ? Optional.empty() : Optional.of(value);
  }

  /** Kurzbeschreibung fuer die Zusammenfassung, leer bei exakten Optionen ohne Auto-Abholung. */
  public static String describe(
      OrderOptions options, java.util.function.Function<String, String> text) {
    List<String> parts = new java.util.ArrayList<>();
    if (options.minDurabilityPercent() < 100) {
      parts.add(
          text.apply("durability")
              .replace("<percent>", Integer.toString(options.minDurabilityPercent())));
    }
    if (options.enchantmentsAtLeast()) {
      parts.add(text.apply("enchants"));
    }
    if (options.autoClaim()) {
      parts.add(text.apply("autoclaim"));
    }
    return String.join(", ", parts);
  }
}
