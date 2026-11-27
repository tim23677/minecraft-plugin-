package dev.orderhub.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Favoriten und Preisalarme eines Spielers.
 *
 * @since 1.0.0
 */
public interface NotificationService {

  /**
   * Ein Favorit mit optionalem Preisalarm.
   *
   * @param itemKey Material-Key des Items
   * @param minPriceAlertMinor Alarm ab diesem Stueckpreis in Minor Units oder {@code null}
   * @since 1.0.0
   */
  record FavoriteView(String itemKey, Long minPriceAlertMinor) {}

  /**
   * Fuegt einen Favoriten hinzu.
   *
   * @param player der Spieler
   * @param itemKey Material-Key
   * @return {@code true}, wenn neu eingetragen
   * @since 1.0.0
   */
  CompletableFuture<Boolean> addFavorite(UUID player, String itemKey);

  /**
   * Entfernt einen Favoriten.
   *
   * @param player der Spieler
   * @param itemKey Material-Key
   * @return {@code true}, wenn etwas entfernt wurde
   * @since 1.0.0
   */
  CompletableFuture<Boolean> removeFavorite(UUID player, String itemKey);

  /**
   * Setzt oder loescht ({@code null}) den Preisalarm eines Favoriten.
   *
   * @param player der Spieler
   * @param itemKey Material-Key eines Favoriten
   * @param minPriceMinor Mindestpreis in Minor Units oder {@code null} zum Loeschen
   * @return {@code true}, wenn der Alarm gesetzt oder geloescht wurde
   * @since 1.0.0
   */
  CompletableFuture<Boolean> setPriceAlert(UUID player, String itemKey, Long minPriceMinor);

  /**
   * Liest die Favoriten eines Spielers.
   *
   * @param player der Spieler
   * @return die Favoriten, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<List<FavoriteView>> getFavorites(UUID player);
}
