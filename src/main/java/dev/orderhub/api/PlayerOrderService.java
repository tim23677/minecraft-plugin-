package dev.orderhub.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Auftraege eines Spielers. */
public interface PlayerOrderService {

  /** Offene (aktive und pausierte) Auftraege, neueste zuerst. Seitengroesse max. 100. */
  CompletableFuture<List<OrderService.OrderView>> getOrders(UUID owner, int offset, int limit);

  CompletableFuture<Integer> countOpenOrders(UUID owner);
}
