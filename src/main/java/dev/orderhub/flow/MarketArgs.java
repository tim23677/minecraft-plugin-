package dev.orderhub.flow;

import dev.orderhub.domain.OrderQuery;
import dev.orderhub.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Liest die Argumente von {@code /order market [sortierung] [suchtext...] [min=<preis>]
 * [max=<preis>]}. Preise akzeptieren {@code k}, {@code m} und {@code b} wie bei {@code /order
 * create}.
 *
 * @since 2.0.0
 */
public final class MarketArgs {

  /** Ergebnis: der Filter oder das erste nicht lesbare Argument. */
  public record Result(MarketFlow.Filter filter, String invalid) {
    public boolean ok() {
      return invalid == null;
    }
  }

  /** Schluesselwoerter fuer die Tab-Vervollstaendigung. */
  public static final List<String> SUGGESTIONS = List.of("min=", "max=");

  private MarketArgs() {}

  /**
   * @param args alle Argumente des Befehls inklusive {@code market}
   */
  public static Result parse(String[] args) {
    OrderQuery.Sort sort = OrderQuery.Sort.NEWEST;
    List<String> words = new ArrayList<>();
    Long min = null;
    Long max = null;
    for (int i = 1; i < args.length; i++) {
      String lower = args[i].toLowerCase(Locale.ROOT);
      if (lower.startsWith("min=") || lower.startsWith("max=")) {
        long value;
        try {
          value = Money.parse(lower.substring(4));
        } catch (NumberFormatException e) {
          return new Result(MarketFlow.Filter.all(), args[i]);
        }
        if (value < 0) {
          return new Result(MarketFlow.Filter.all(), args[i]);
        }
        if (lower.startsWith("min=")) {
          min = value;
        } else {
          max = value;
        }
      } else if (words.isEmpty() && sortOf(lower) != null && sort == OrderQuery.Sort.NEWEST) {
        sort = sortOf(lower);
      } else {
        words.add(args[i]);
      }
    }
    if (min != null && max != null && min > max) {
      return new Result(MarketFlow.Filter.all(), "min=" + Money.format(min));
    }
    String text = words.isEmpty() ? null : String.join(" ", words);
    return new Result(new MarketFlow.Filter(sort, null, text, min, max), null);
  }

  private static OrderQuery.Sort sortOf(String raw) {
    try {
      return OrderQuery.Sort.valueOf(raw.replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
