package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.api.event.OrderRenewEvent;
import dev.orderhub.api.event.SuspiciousTradeEvent;
import dev.orderhub.bootstrap.Settings;
import dev.orderhub.service.OrderService;
import dev.orderhub.tx.DeliveryCommitted;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Die neuen API-Ereignisse kommen an, und zwar nie vom Haupt-Thread aus. */
class NewApiEventsTest extends FlowTestBase {

  static final class Collector implements Listener {
    final List<OrderRenewEvent> renews = new ArrayList<>();
    final List<SuspiciousTradeEvent> suspicious = new ArrayList<>();
    final List<Boolean> asyncFlags = new ArrayList<>();

    @EventHandler
    public void onRenew(OrderRenewEvent e) {
      renews.add(e);
      asyncFlags.add(e.isAsynchronous());
    }

    @EventHandler
    public void onSuspicious(SuspiciousTradeEvent e) {
      suspicious.add(e);
      asyncFlags.add(e.isAsynchronous());
    }
  }

  private static void onWorkerThread(Runnable task) throws InterruptedException {
    Thread t = new Thread(task, "event-worker");
    t.start();
    t.join();
  }

  @Test
  void renewingFiresAnEventFromAWorkerThread() throws Exception {
    Collector collector = new Collector();
    server.getPluginManager().registerEvents(collector, plugin);
    h.economy.setBalance(supplier.getUniqueId(), 10_000_000);
    long id =
        h.orders
            .createOrder(
                new OrderService.CreateOrderRequest(
                    UUID.randomUUID(),
                    supplier.getUniqueId(),
                    codec.encode(new ItemStack(Material.DIAMOND)),
                    100,
                    5,
                    1,
                    null,
                    false,
                    "ores",
                    100,
                    false))
            .orderId();
    scheduler.deferAsync = true;

    new RenewFlow(services).handle(supplier, new String[] {"renew", String.valueOf(id), "on"});
    scheduler.deferAsync = true;
    onWorkerThread(scheduler::drain);

    assertThat(collector.renews).hasSize(1);
    assertThat(collector.renews.get(0).orderId()).isEqualTo(id);
    assertThat(collector.renews.get(0).owner()).isEqualTo(supplier.getUniqueId());
    assertThat(collector.renews.get(0).autoRenew()).isTrue();
    assertThat(collector.asyncFlags).containsOnly(true);
  }

  @Test
  void aSuspiciousPairFiresOnceWhenTheThresholdIsReached() throws Exception {
    Collector collector = new Collector();
    server.getPluginManager().registerEvents(collector, plugin);
    YamlConfiguration yaml = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    yaml.set("anti-exploit.pair-trades-per-day", 2);
    Settings settings = Settings.from(yaml);
    Services s = services;
    Services strict =
        new Services(
            s.logger(),
            s.storage(),
            s.scheduler(),
            s.messages(),
            () -> settings,
            s.rates(),
            s.orders(),
            s.delivery(),
            s.claims(),
            s.returns(),
            s.payouts(),
            s.codec(),
            s.matcher(),
            s.menus(),
            s.insights(),
            s.discord());
    h.economy.setBalance(OWNER, 1_000_000_000);
    long orderId = h.create(OWNER, 100, 100, 1).orderId();
    UUID sup = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    PairWatch watch = new PairWatch(strict);

    onWorkerThread(() -> {
      for (int i = 0; i < 4; i++) {
        h.deliver(orderId, sup, 1);
        watch.check(
            new DeliveryCommitted(
                UUID.randomUUID(),
                orderId,
                "minecraft:diamond",
                sup,
                OWNER,
                1,
                100,
                100,
                0,
                100,
                false,
                0,
                "t"));
      }
    });

    assertThat(collector.suspicious).hasSize(1);
    var e = collector.suspicious.get(0);
    assertThat(e.supplier()).isEqualTo(sup);
    assertThat(e.owner()).isEqualTo(OWNER);
    assertThat(e.count()).isEqualTo(2);
    assertThat(e.orderId()).isEqualTo(orderId);
  }
}
