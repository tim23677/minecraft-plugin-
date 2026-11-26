package dev.orderhub.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Favoriten und Preisalarme eines Spielers. */
public interface NotificationService {

  record FavoriteView(String itemKey, Long minPriceAlertMinor) {}

  CompletableFuture<Boolean> addFavorite(UUID player, String itemKey);

  CompletableFuture<Boolean> removeFavorite(UUID player, String itemKey);

  /** Setzt oder loescht ({@code null}) den Preisalarm eines Favoriten. */
  CompletableFuture<Boolean> setPriceAlert(UUID player, String itemKey, Long minPriceMinor);

  CompletableFuture<List<FavoriteView>> getFavorites(UUID player);
}
