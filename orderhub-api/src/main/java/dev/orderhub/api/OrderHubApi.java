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
 * (Semantic Versioning ueber {@link #API_VERSION} und die Version des Moduls {@code orderhub-api}).
 * Neue Methoden kommen als {@code default}.
 *
 * @since 1.0.0
 */
public interface OrderHubApi {

  /**
   * Aktuelle API-Version. Wird bei jeder kompatiblen Erweiterung erhoeht.
   *
   * @since 1.0.0
   */
  int API_VERSION = 1;

  /**
   * Version der API, die das laufende OrderHub bereitstellt.
   *
   * @return {@link #API_VERSION} des laufenden Plugins
   * @since 1.0.0
   */
  int apiVersion();

  /**
   * Auftraege lesen, erstellen und stornieren.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  OrderService orders();

  /**
   * Auftraege eines Spielers.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  PlayerOrderService playerOrders();

  /**
   * Marktdaten.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  MarketService market();

  /**
   * Spielerstatistik.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  StatisticsService statistics();

  /**
   * Lesender Zugriff auf das Transaction Journal.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  TransactionService transactions();

  /**
   * Favoriten und Preisalarme.
   *
   * @return der Dienst, nie {@code null}
   * @since 1.0.0
   */
  NotificationService notifications();
}
