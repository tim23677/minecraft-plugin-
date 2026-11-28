package dev.orderhub.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reine Marktberechnungen auf echten Daten. Es gibt keine Prognosen: Empfehlungen sind Quantile
 * tatsaechlicher Abschlusspreise, und bei zu wenig Daten liefern sie nichts.
 */
public final class MarketMath {

  private MarketMath() {}

  /** Auswertung abgeschlossener Geschaefte. */
  public record TradeSummary(
      int trades,
      long volumeUnits,
      long volumeMoney,
      long medianUnitPrice,
      long averageUnitPrice) {}

  /** Auswertung der offenen Nachfrage. */
  public record DemandSummary(
      int orders,
      long openUnits,
      long lowestUnitPrice,
      long highestUnitPrice,
      long medianUnitPrice,
      long averageUnitPrice) {}

  /** Empfohlener Preisbereich, abgeleitet aus dem 25. und 75. Perzentil der Abschlusspreise. */
  public record Recommendation(long low, long median, long high, int trades) {}

  /** Geschaefte mit {@code at >= since}. Leer bei keinen Daten. */
  public static Optional<TradeSummary> summarizeTrades(List<Trade> trades, long since) {
    List<Long> prices = new ArrayList<>();
    long units = 0;
    long money = 0;
    for (Trade t : trades) {
      if (t.at() < since) {
        continue;
      }
      prices.add(t.unitPrice());
      units += t.quantity();
      money = Math.addExact(money, Math.multiplyExact(t.unitPrice(), (long) t.quantity()));
    }
    if (prices.isEmpty()) {
      return Optional.empty();
    }
    long avg = Math.floorDiv(money, units);
    return Optional.of(new TradeSummary(prices.size(), units, money, median(prices), avg));
  }

  /** Offene Nachfrage. Der Durchschnitt ist mengengewichtet. */
  public static Optional<DemandSummary> summarizeDemand(List<OpenOrderView> orders) {
    List<OpenOrderView> open = orders.stream().filter(o -> o.remaining() > 0).toList();
    if (open.isEmpty()) {
      return Optional.empty();
    }
    long units = 0;
    long money = 0;
    long low = Long.MAX_VALUE;
    long high = 0;
    List<Long> prices = new ArrayList<>();
    for (OpenOrderView o : open) {
      units += o.remaining();
      money = Math.addExact(money, Math.multiplyExact(o.unitPrice(), (long) o.remaining()));
      low = Math.min(low, o.unitPrice());
      high = Math.max(high, o.unitPrice());
      prices.add(o.unitPrice());
    }
    return Optional.of(
        new DemandSummary(
            open.size(), units, low, high, median(prices), Math.floorDiv(money, units)));
  }

  /**
   * Preisempfehlung. Gibt nur etwas zurueck, wenn im Zeitraum mindestens {@code minTrades}
   * abgeschlossene Geschaefte vorliegen.
   */
  public static Optional<Recommendation> recommend(List<Trade> trades, long since, int minTrades) {
    List<Long> prices =
        trades.stream().filter(t -> t.at() >= since).map(Trade::unitPrice).sorted().toList();
    if (prices.size() < Math.max(1, minTrades)) {
      return Optional.empty();
    }
    return Optional.of(
        new Recommendation(
            percentile(prices, 25), percentile(prices, 50), percentile(prices, 75), prices.size()));
  }

  /**
   * Median einer nicht leeren Liste. Bei gerader Anzahl der abgerundete Mittelwert beider Mitten.
   */
  public static long median(List<Long> values) {
    List<Long> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    if (n == 0) {
      throw new IllegalArgumentException("empty");
    }
    return n % 2 == 1
        ? sorted.get(n / 2)
        : Math.floorDiv(Math.addExact(sorted.get(n / 2 - 1), sorted.get(n / 2)), 2L);
  }

  /** Nearest-Rank-Perzentil auf einer aufsteigend sortierten Liste. */
  static long percentile(List<Long> sortedAscending, int percent) {
    int n = sortedAscending.size();
    int rank = (int) Math.ceil(percent / 100.0 * n);
    return sortedAscending.get(Math.max(0, Math.min(n - 1, rank - 1)));
  }

  /**
   * Durchschnittlicher Stueckpreis je Tag, gewichtet nach Menge. Das Feld {@code days - 1} ist der
   * Tag, an dem {@code nowMillis} liegt, Feld 0 der aelteste. Tage ohne Handel haben den Wert 0.
   */
  public static long[] dailyAverages(List<Trade> trades, long nowMillis, int days) {
    if (days < 1) {
      throw new IllegalArgumentException("days");
    }
    long day = 24L * 3_600_000L;
    long[] money = new long[days];
    long[] units = new long[days];
    for (Trade t : trades) {
      long age = Math.floorDiv(nowMillis - t.at(), day);
      if (age < 0 || age >= days) {
        continue;
      }
      int slot = days - 1 - (int) age;
      money[slot] =
          Math.addExact(money[slot], Math.multiplyExact(t.unitPrice(), (long) t.quantity()));
      units[slot] += t.quantity();
    }
    long[] out = new long[days];
    for (int i = 0; i < days; i++) {
      out[i] = units[i] == 0 ? 0 : Math.floorDiv(money[i], units[i]);
    }
    return out;
  }
}
