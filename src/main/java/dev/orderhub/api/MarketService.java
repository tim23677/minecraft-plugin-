package dev.orderhub.api;

import java.util.concurrent.CompletableFuture;

/** Marktdaten aus abgeschlossenen Lieferungen und offenen Auftraegen. */
public interface MarketService {

  /**
   * Marktstatistik. Felder sind {@code null}, wenn die Datengrundlage fehlt (keine Geschaefte oder
   * keine offene Nachfrage). Es gibt keine Prognosen.
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

  CompletableFuture<MarketStatistics> getMarketStatistics(String itemKey, int days);
}
