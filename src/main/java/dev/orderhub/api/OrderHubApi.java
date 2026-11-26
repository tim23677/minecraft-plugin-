package dev.orderhub.api;

/**
 * Einstieg in die OrderHub-API, ueber den Bukkit-{@code ServicesManager} abrufbar:
 *
 * <pre>{@code
 * RegisteredServiceProvider<OrderHubApi> rsp =
 *     Bukkit.getServicesManager().getRegistration(OrderHubApi.class);
 * }</pre>
 *
 * Alle Methoden liefern {@link java.util.concurrent.CompletableFuture} und duerfen von jedem Thread
 * aufgerufen werden. Die Ergebnisse werden nie auf dem Server-Thread berechnet. Wer in einem
 * Future-Callback Bukkit-Objekte anfasst, muss selbst auf den zustaendigen Scheduler wechseln.
 * Betraege sind {@code long} in Minor Units (zwei Nachkommastellen, 1,00 = 100).
 *
 * <p>Stabilitaet: Interfaces und Records dieses Pakets werden nur erweitert, nie veraendert
 * (Semantic Versioning ueber {@link #API_VERSION}). Neue Methoden kommen als {@code default}.
 */
public interface OrderHubApi {

  /** Aktuelle API-Version. Wird bei jeder kompatiblen Erweiterung erhoeht. */
  int API_VERSION = 1;

  int apiVersion();

  OrderService orders();

  PlayerOrderService playerOrders();

  MarketService market();

  StatisticsService statistics();

  TransactionService transactions();

  NotificationService notifications();
}
