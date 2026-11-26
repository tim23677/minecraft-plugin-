package dev.orderhub.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Spielerstatistik. */
public interface StatisticsService {

  record PlayerStatistics(
      UUID player,
      long ordersCreated,
      long ordersCompleted,
      long itemsDelivered,
      long itemsReceived,
      long earnedMinor,
      long spentMinor) {}

  /** Leer, wenn der Spieler noch nie gehandelt hat. */
  CompletableFuture<Optional<PlayerStatistics>> getPlayerStatistics(UUID player);
}
