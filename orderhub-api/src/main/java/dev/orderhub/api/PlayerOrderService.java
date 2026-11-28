package dev.orderhub.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Auftraege eines Spielers.
 *
 * @since 1.0.0
 */
public interface PlayerOrderService {

  /**
   * Offene (aktive und pausierte) Auftraege, neueste zuerst. Seitengroesse max. 100.
   *
   * @param owner der Spieler
   * @param offset Start der Seite
   * @param limit Seitengroesse, auf 1 bis 100 begrenzt
   * @return die Seite, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<List<OrderService.OrderView>> getOrders(UUID owner, int offset, int limit);

  /**
   * Anzahl offener Auftraege des Spielers.
   *
   * @param owner der Spieler
   * @return die Anzahl
   * @since 1.0.0
   */
  CompletableFuture<Integer> countOpenOrders(UUID owner);
}
