package dev.orderhub.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spielerstatistik.
 *
 * @since 1.0.0
 */
public interface StatisticsService {

  /**
   * Zahlen eines Spielers.
   *
   * @param player der Spieler
   * @param ordersCreated erstellte Auftraege
   * @param ordersCompleted vollstaendig erfuellte Auftraege
   * @param itemsDelivered gelieferte Items
   * @param itemsReceived erhaltene Items
   * @param earnedMinor Einnahmen in Minor Units
   * @param spentMinor Ausgaben in Minor Units
   * @since 1.0.0
   */
  record PlayerStatistics(
      UUID player,
      long ordersCreated,
      long ordersCompleted,
      long itemsDelivered,
      long itemsReceived,
      long earnedMinor,
      long spentMinor) {}

  /**
   * Liest die Statistik eines Spielers.
   *
   * @param player der Spieler
   * @return leer, wenn der Spieler noch nie gehandelt hat
   * @since 1.0.0
   */
  CompletableFuture<Optional<PlayerStatistics>> getPlayerStatistics(UUID player);
}
