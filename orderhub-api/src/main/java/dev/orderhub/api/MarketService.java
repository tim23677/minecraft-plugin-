package dev.orderhub.api;

import java.util.concurrent.CompletableFuture;

/**
 * Marktdaten aus abgeschlossenen Lieferungen und offenen Auftraegen.
 *
 * @since 1.0.0
 */
public interface MarketService {

  /**
   * Marktstatistik. Felder sind {@code null}, wenn die Datengrundlage fehlt (keine Geschaefte oder
   * keine offene Nachfrage). Es gibt keine Prognosen.
   *
   * @param itemKey Material-Key des Items
   * @param days Zeitfenster in Tagen
   * @param trades Anzahl abgeschlossener Geschaefte im Fenster
   * @param tradedUnits gehandelte Stueckzahl im Fenster
   * @param medianTradePriceMinor Median des Stueckpreises der Geschaefte oder {@code null}
   * @param averageTradePriceMinor Durchschnitt des Stueckpreises der Geschaefte oder {@code null}
   * @param openOrders Anzahl offener Auftraege
   * @param openUnits offene Stueckzahl
   * @param lowestOpenPriceMinor niedrigster offener Stueckpreis oder {@code null}
   * @param highestOpenPriceMinor hoechster offener Stueckpreis oder {@code null}
   * @param recommendedLowMinor untere Grenze der Preisempfehlung oder {@code null}
   * @param recommendedHighMinor obere Grenze der Preisempfehlung oder {@code null}
   * @since 1.0.0
   */
  record MarketStatistics(
      String itemKey,
      int days,
      int trades,
      long tradedUnits,
      Long medianTradePriceMinor,
      Long averageTradePriceMinor,
      int openOrders,
      long openUnits,
      Long lowestOpenPriceMinor,
      Long highestOpenPriceMinor,
      Long recommendedLowMinor,
      Long recommendedHighMinor) {}

  /**
   * Liest die Marktstatistik eines Items.
   *
   * @param itemKey Material-Key, zum Beispiel {@code minecraft:diamond}
   * @param days Zeitfenster in Tagen
   * @return die Statistik, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<MarketStatistics> getMarketStatistics(String itemKey, int days);
}
