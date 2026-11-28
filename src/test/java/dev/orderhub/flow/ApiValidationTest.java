package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.api.OrderService;
import dev.orderhub.discord.DiscordService;
import dev.orderhub.integration.ApiImpl;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Jede API-Methode gegen Grenzwerte, Null, negative Mengen, fremde UUIDs und gesperrte Items. Eine
 * ungueltige Eingabe darf nie buchen, nie Daten anderer Spieler liefern und nie den Server-Thread
 * blockieren.
 */
class ApiValidationTest extends FlowTestBase {

  private JdbcStorage insightStorage;
  private ApiImpl api;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("api.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("api"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    InsightsService insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    Services base = services;
    api =
        new ApiImpl(
            new Services(
                base.logger(),
                base.storage(),
                base.scheduler(),
                base.messages(),
                base.settings(),
                base.rates(),
                base.orders(),
                base.delivery(),
                base.claims(),
                base.returns(),
                base.payouts(),
                base.codec(),
                base.matcher(),
                base.menus(),
                insights,
                base.discord()));
    // Der Auftraggeber (supplier) darf erstellen
    supplier.addAttachment(plugin, "orderhub.create", true);
    h.economy.setBalance(supplier.getUniqueId(), 10_000_000);
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private OrderService.CreateRequest request(ItemStack item, int qty, long price, Duration life) {
    return new OrderService.CreateRequest(
        UUID.randomUUID(), supplier.getUniqueId(), item, qty, price, life);
  }

  private OrderService.CreateResult create(OrderService.CreateRequest r) {
    return api.orders().createOrder(r).join();
  }

  private void assertNothingBooked() {
    assertThat(h.economy.mutationCount()).as("keine Buchung").isZero();
    assertThat(h.storage.snapshot().orders()).isEmpty();
  }

  // ---- createOrder

  @Test
  void aNullRequestOrMissingFieldsGiveErrorAndNoBooking() {
    assertThat(create(null).status()).isEqualTo(OrderService.CreateStatus.ERROR);
    assertThat(
            create(
                    new OrderService.CreateRequest(
                        null,
                        supplier.getUniqueId(),
                        new ItemStack(Material.DIAMOND),
                        1,
                        100,
                        null))
                .status())
        .isEqualTo(OrderService.CreateStatus.ERROR);
    assertThat(
            create(
                    new OrderService.CreateRequest(
                        UUID.randomUUID(), null, new ItemStack(Material.DIAMOND), 1, 100, null))
                .status())
        .isEqualTo(OrderService.CreateStatus.ERROR);
    assertThat(create(request(null, 1, 100, null)).status())
        .isEqualTo(OrderService.CreateStatus.ERROR);
    assertNothingBooked();
  }

  @Test
  void nonPositiveAndOverflowingQuantitiesAreRejectedBeforeAnyBooking() {
    for (int quantity : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      assertThat(create(request(new ItemStack(Material.DIAMOND), quantity, 100, null)).status())
          .as("quantity %d", quantity)
          .isEqualTo(OrderService.CreateStatus.INVALID_QUANTITY);
    }
    assertNothingBooked();
  }

  @Test
  void nonPositiveTooLargeAndOverflowingPricesAreRejectedBeforeAnyBooking() {
    for (long price : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
      var status = create(request(new ItemStack(Material.DIAMOND), 10, price, null)).status();
      assertThat(status)
          .as("price %d", price)
          .isIn(
              OrderService.CreateStatus.INVALID_PRICE, OrderService.CreateStatus.INVALID_QUANTITY);
    }
    // Stueckpreis x Menge ueberlaeuft, obwohl beides einzeln im Rahmen liegt
    assertThat(
            create(request(new ItemStack(Material.DIAMOND), 1_000_000, Long.MAX_VALUE / 2, null))
                .status())
        .isNotEqualTo(OrderService.CreateStatus.CREATED);
    assertNothingBooked();
  }

  @Test
  void blockedAndEmptyItemsAreRejected() {
    assertThat(create(request(new ItemStack(Material.BEDROCK), 1, 100, null)).status())
        .isEqualTo(OrderService.CreateStatus.ITEM_REJECTED);
    assertThat(create(request(new ItemStack(Material.AIR), 1, 100, null)).status())
        .isIn(OrderService.CreateStatus.ITEM_REJECTED, OrderService.CreateStatus.ERROR);
    assertNothingBooked();
  }

  @Test
  void anOwnerWhoIsNotOnlineOrHasNoPermissionIsDeniedWithoutBypass() {
    UUID stranger = UUID.randomUUID();
    var result =
        create(
            new OrderService.CreateRequest(
                UUID.randomUUID(), stranger, new ItemStack(Material.DIAMOND), 1, 100, null));
    assertThat(result.status()).isEqualTo(OrderService.CreateStatus.NO_PERMISSION);
    assertNothingBooked();
  }

  @Test
  void aBypassForAStrangerWithoutMoneyStillCannotBookAnything() {
    UUID stranger = UUID.randomUUID();
    var result =
        api.orders()
            .createOrder(
                new OrderService.CreateRequest(
                    UUID.randomUUID(), stranger, new ItemStack(Material.DIAMOND), 1, 100, null),
                new OrderService.CreateOptions(true))
            .join();
    assertThat(result.status()).isEqualTo(OrderService.CreateStatus.INSUFFICIENT_FUNDS);
    assertThat(h.storage.snapshot().orders()).isEmpty();
  }

  @Test
  void negativeZeroAndHugeLifetimesNeverCrashAndAreHandledPredictably() {
    for (Duration life :
        new Duration[] {Duration.ZERO, Duration.ofSeconds(-5), Duration.ofDays(4000), null}) {
      var result = create(request(new ItemStack(Material.DIAMOND), 1, 100, life));
      assertThat(result.status()).as("lifetime %s", life).isNotNull();
    }
  }

  @Test
  void aRepeatedRequestIdNeverCreatesASecondOrder() {
    var r = request(new ItemStack(Material.DIAMOND), 2, 100, null);
    var first = create(r);
    var second = create(r);
    assertThat(first.status()).isEqualTo(OrderService.CreateStatus.CREATED);
    assertThat(second.status()).isEqualTo(OrderService.CreateStatus.ALREADY_CREATED);
    assertThat(h.storage.snapshot().orders()).hasSize(1);
    assertThat(h.economy.count(dev.orderhub.testsupport.InMemoryEconomy.Op.WITHDRAW)).isEqualTo(1);
  }

  @Test
  void changingTheItemAfterTheCallCannotChangeTheOrder() {
    ItemStack item = new ItemStack(Material.DIAMOND, 1);
    var request = request(item, 1, 100, null);
    item.setAmount(64);
    assertThat(request.item().getAmount()).isEqualTo(1);
  }

  // ---- lesen

  @Test
  void readingWithNonsenseIdsAndPagesIsHarmless() {
    assertThat(api.orders().getOrder(0).join()).isEmpty();
    assertThat(api.orders().getOrder(-5).join()).isEmpty();
    assertThat(api.orders().getOrder(Long.MAX_VALUE).join()).isEmpty();
    assertThat(api.orders().getOpenOrders(null).join()).isEmpty();
    for (int limit : new int[] {Integer.MIN_VALUE, -1, 0, 1, 100, 101, Integer.MAX_VALUE}) {
      assertThat(api.orders().getOpenOrders(new OrderService.Filter(null, null, -10, limit)).join())
          .as("limit %d", limit)
          .hasSizeLessThanOrEqualTo(100);
    }
  }

  @Test
  void aMalformedItemKeyInAFilterOrMarketOrNotificationCallFailsTheFuture() {
    String[] bad = {
      "",
      "diamond",
      "MINECRAFT:DIAMOND",
      "minecraft:dia mond",
      "x'; DROP TABLE orders;--:y",
      "a:".repeat(200)
    };
    for (String key : bad) {
      assertThatThrownBy(
              () -> api.orders().getOpenOrders(new OrderService.Filter(key, null, 0, 10)).join())
          .as("filter %s", key)
          .hasCauseInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> api.market().getMarketStatistics(key, 7).join())
          .as("market %s", key)
          .hasCauseInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> api.notifications().addFavorite(supplier.getUniqueId(), key).join())
          .as("favorite %s", key)
          .hasCauseInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> api.market().getMarketStatistics(null, 7).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void marketDaysAreClampedAndAValidKeyWithoutDataGivesEmptyStatistics() {
    for (int days : new int[] {Integer.MIN_VALUE, -1, 0, 1, 90, 91, Integer.MAX_VALUE}) {
      var stats = api.market().getMarketStatistics("minecraft:diamond", days).join();
      assertThat(stats.days()).as("days %d", days).isBetween(1, 90);
      assertThat(stats.trades()).isZero();
      assertThat(stats.medianTradePriceMinor()).isNull();
    }
  }

  @Test
  void nullPlayersOrIdsFailTheFutureInsteadOfThrowingOnTheCallingThread() {
    assertThatThrownBy(() -> api.playerOrders().getOrders(null, 0, 10).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.playerOrders().countOpenOrders(null).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.statistics().getPlayerStatistics(null).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.transactions().getTransaction(null).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.transactions().getTransactions(null, 10).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.notifications().getFavorites(null).join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aForeignPlayerSeesOnlyTheirOwnEmptyData() {
    UUID stranger = UUID.randomUUID();
    assertThat(api.playerOrders().getOrders(stranger, 0, 10).join()).isEmpty();
    assertThat(api.playerOrders().countOpenOrders(stranger).join()).isZero();
    assertThat(api.statistics().getPlayerStatistics(stranger).join()).isEmpty();
    assertThat(api.transactions().getTransactions(stranger, 1_000).join()).isEmpty();
    assertThat(api.transactions().getTransaction(UUID.randomUUID()).join()).isEmpty();
    assertThat(api.notifications().getFavorites(stranger).join()).isEmpty();
  }

  // ---- stornieren und Alarme

  @Test
  void cancellingWithNullOrForeignActorsOrUnknownIdsNeverCancels() {
    var created = create(request(new ItemStack(Material.DIAMOND), 3, 100, null));
    long id = created.orderId();
    assertThat(api.orders().cancelOrder(id, null).join().status())
        .isEqualTo(OrderService.CancelStatus.ERROR);
    assertThat(api.orders().cancelOrder(id, UUID.randomUUID()).join().status())
        .isEqualTo(OrderService.CancelStatus.NOT_OWNER);
    assertThat(api.orders().cancelOrder(-1, supplier.getUniqueId()).join().status())
        .isEqualTo(OrderService.CancelStatus.NOT_FOUND);
    assertThat(api.orders().cancelOrder(Long.MAX_VALUE, supplier.getUniqueId()).join().status())
        .isEqualTo(OrderService.CancelStatus.NOT_FOUND);
    assertThat(h.storage.snapshot().orders())
        .singleElement()
        .satisfies(o -> assertThat(o.status().name()).isEqualTo("ACTIVE"));
    assertThat(api.orders().cancelOrder(id, supplier.getUniqueId()).join().status())
        .isEqualTo(OrderService.CancelStatus.CANCELLED);
    assertThat(api.orders().cancelOrder(id, supplier.getUniqueId()).join().status())
        .isIn(OrderService.CancelStatus.ALREADY_DONE, OrderService.CancelStatus.INVALID_STATE);
  }

  @Test
  void priceAlertsRejectNonPositiveValues() {
    UUID p = supplier.getUniqueId();
    assertThat(api.notifications().addFavorite(p, "minecraft:diamond").join()).isTrue();
    assertThat(api.notifications().setPriceAlert(p, "minecraft:diamond", 0L).join()).isFalse();
    assertThat(api.notifications().setPriceAlert(p, "minecraft:diamond", -5L).join()).isFalse();
    assertThat(api.notifications().setPriceAlert(p, "minecraft:diamond", 500L).join()).isTrue();
    assertThat(api.notifications().getFavorites(p).join())
        .singleElement()
        .satisfies(f -> assertThat(f.minPriceAlertMinor()).isEqualTo(500L));
  }

  @Test
  void theValidItemKeyPatternAcceptsRealKeysAndRejectsTheRest() {
    assertThat(ApiImpl.validItemKey("minecraft:diamond")).isTrue();
    assertThat(ApiImpl.validItemKey("itemsadder:ruby_ore")).isTrue();
    assertThat(ApiImpl.validItemKey("minecraft:custom/path.item-1")).isTrue();
    assertThat(ApiImpl.validItemKey("diamond")).isFalse();
    assertThat(ApiImpl.validItemKey("Minecraft:Diamond")).isFalse();
    assertThat(ApiImpl.validItemKey(null)).isFalse();
  }
}
