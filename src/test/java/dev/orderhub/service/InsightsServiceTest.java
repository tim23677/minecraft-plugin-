package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import dev.orderhub.tx.DeliveryCommitted;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InsightsServiceTest {

  @TempDir Path dir;
  private JdbcStorage storage;
  private InsightsService insights;
  private DiscordService discord;

  @BeforeEach
  void setUp() throws Exception {
    storage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("t.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    discord =
        new DiscordService(
            storage,
            () ->
                new DiscordService.Config(
                    true,
                    "OrderHub",
                    Set.of(DiscordService.Event.LARGE_ORDER, DiscordService.Event.ORDER_COMPLETED),
                    1_000_000),
            Logger.getLogger("test"));
    insights = new InsightsService(storage, discord, () -> 60, () -> 3);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private DeliveryCommitted delivery(
      UUID journal, UUID supplier, UUID owner, int qty, long unit, boolean done) {
    return new DeliveryCommitted(
        journal,
        1,
        "minecraft:diamond",
        supplier,
        owner,
        qty,
        unit,
        qty * unit,
        qty * unit / 50,
        qty * unit - qty * unit / 50,
        done,
        1L,
        "test");
  }

  @Test
  void deliveryIsCountedExactlyOnce() {
    UUID supplier = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID journal = UUID.randomUUID();
    DeliveryCommitted e = delivery(journal, supplier, owner, 4, 800, true);
    insights.deliveryCommitted(e);
    insights.deliveryCommitted(e); // Wiederholung (z. B. nach Absturz) zaehlt nicht doppelt
    var s = insights.stats(supplier).orElseThrow();
    assertThat(s.itemsDelivered()).isEqualTo(4);
    assertThat(insights.stats(owner).orElseThrow().ordersCompleted()).isEqualTo(1);
    var view = insights.market("minecraft:diamond", 7, System.currentTimeMillis());
    assertThat(view.trades()).isPresent();
    assertThat(view.trades().orElseThrow().volumeUnits()).isEqualTo(4);
  }

  @Test
  void recommendationNeedsEnoughRealTrades() {
    UUID owner = UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      insights.deliveryCommitted(
          delivery(UUID.randomUUID(), UUID.randomUUID(), owner, 1, 800 + i, false));
    }
    long now = System.currentTimeMillis() + 60_000;
    var view = insights.market("minecraft:diamond", 7, now);
    assertThat(view.trades()).isPresent();
    assertThat(view.recommendation()).isEmpty(); // 2 Geschaefte < 3
    insights.deliveryCommitted(
        delivery(UUID.randomUUID(), UUID.randomUUID(), owner, 1, 900, false));
    assertThat(insights.market("minecraft:diamond", 7, now).recommendation()).isPresent();
  }

  @Test
  void priceAlertNotifiesOnceAndRespectsCooldownAndOwner() {
    UUID watcher = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    insights.addFavorite(watcher, "minecraft:diamond");
    insights.addFavorite(owner, "minecraft:diamond");
    insights.setAlert(watcher, "minecraft:diamond", 500L);
    insights.setAlert(owner, "minecraft:diamond", 500L);
    assertThat(insights.orderCreated(10, owner, "minecraft:diamond", 400, 5)).isEmpty();
    List<UUID> hit = insights.orderCreated(11, owner, "minecraft:diamond", 600, 5);
    assertThat(hit).containsExactly(watcher); // Auftraggeber bekommt keinen Alarm
    assertThat(insights.orderCreated(12, owner, "minecraft:diamond", 700, 5)).isEmpty(); // Cooldown
    var notes = insights.takeNotes(watcher, 10);
    assertThat(notes).hasSize(1);
    assertThat(insights.takeNotes(watcher, 10)).isEmpty();
  }

  @Test
  void largeOrdersAndCompletionGoToDiscordOutbox() {
    insights.orderCreated(
        1, UUID.randomUUID(), "minecraft:diamond", 100_00, 5); // 500,00 < 10.000: nein
    insights.orderCreated(2, UUID.randomUUID(), "minecraft:diamond", 100_000_00, 1); // gross: ja
    insights.deliveryCommitted(
        delivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 5, true));
    var items = storage.transact(u -> u.outbox().dueDiscord(Long.MAX_VALUE, 50)).join();
    assertThat(items).hasSize(2);
    assertThat(items)
        .allSatisfy(i ->
            assertThat(i.payload())
                .doesNotContainPattern(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
  }
}
