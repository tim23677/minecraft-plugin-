package dev.example.orderlistener;

import dev.orderhub.api.OrderHubApi;
import dev.orderhub.api.event.OrderCompleteEvent;
import dev.orderhub.api.event.OrderDeliveryEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Beispiel fuer die OrderHub-API: hoert auf Lieferungen und fertige Auftraege und fragt beim Start
 * die Marktdaten eines Items ab. Alle Ereignisse kommen asynchron, deshalb faesst der Handler
 * keine Bukkit-Welt- oder Entity-Daten an.
 */
public final class OrderListenerPlugin extends JavaPlugin implements Listener {

  @Override
  public void onEnable() {
    RegisteredServiceProvider<OrderHubApi> registration =
        getServer().getServicesManager().getRegistration(OrderHubApi.class);
    if (registration == null) {
      getLogger().warning("OrderHub is not enabled, this example does nothing.");
      return;
    }
    OrderHubApi api = registration.getProvider();
    getServer().getPluginManager().registerEvents(this, this);
    api.market()
        .getMarketStatistics("minecraft:diamond", 7)
        .thenAccept(
            stats ->
                getLogger()
                    .info(
                        "diamond: "
                            + stats.trades()
                            + " trades in 7 days, "
                            + stats.openOrders()
                            + " open orders"))
        .exceptionally(
            error -> {
              getLogger().warning("market query failed: " + error.getMessage());
              return null;
            });
  }

  @EventHandler
  public void onDelivery(OrderDeliveryEvent event) {
    getLogger()
        .info(
            "order #"
                + event.orderId()
                + ": "
                + event.quantity()
                + "x "
                + event.itemKey()
                + " delivered, net payout "
                + event.netPayoutMinor());
  }

  @EventHandler
  public void onComplete(OrderCompleteEvent event) {
    getLogger().info("order #" + event.orderId() + " is complete");
  }
}
