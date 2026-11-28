package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.placeholder.PlaceholderCache;
import dev.orderhub.placeholder.PlaceholderResolver;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Marktplatzhalter aus echten Daten einer SQLite-Datenbank. */
class MarketPlaceholderTest extends FlowTestBase {

  private JdbcStorage insightStorage;
  private PlaceholderResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("market.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    InsightsService insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    Services base = services;
    Services withInsights =
        new Services(
            base.logger(),
            insightStorage,
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
            base.discord());
    resolver =
        new PlaceholderResolver(
            withInsights, new PlaceholderCache(100, 60_000, () -> 1L, Runnable::run));
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private void order(String material, long price) throws Exception {
    var snapshot = codec.encode(new ItemStack(Material.valueOf(material)));
    long now = System.currentTimeMillis();
    insightStorage
        .transact(uow ->
            uow.orders()
                .insert(
                    new NewOrder(
                        UUID.randomUUID(),
                        "srv",
                        snapshot,
                        price,
                        5,
                        1,
                        now + 3_600_000L,
                        false,
                        "gems"),
                    now))
        .join();
  }

  @Test
  void highestAndLowestUseTheOpenOrdersOfTheItem() throws Exception {
    order("DIAMOND", 300);
    order("DIAMOND", 700);
    order("DIAMOND", 500);
    order("EMERALD", 9_000);

    resolver.resolve(null, "market_highest_diamond");
    resolver.resolve(null, "market_lowest_diamond");

    assertThat(resolver.resolve(null, "market_highest_diamond")).isEqualTo("7.00");
    assertThat(resolver.resolve(null, "market_lowest_diamond")).isEqualTo("3.00");
  }

  @Test
  void anItemWithoutOrdersShowsADash() {
    resolver.resolve(null, "market_highest_gold_ingot");
    assertThat(resolver.resolve(null, "market_highest_gold_ingot")).isEqualTo("-");
    resolver.resolve(null, "market_lowest_gold_ingot");
    assertThat(resolver.resolve(null, "market_lowest_gold_ingot")).isEqualTo("-");
  }

  @Test
  void theItemNameIsCleanedAndNamespacedItemsWork() throws Exception {
    order("DIAMOND", 400);
    resolver.resolve(null, "market_highest_minecraft:diamond");
    assertThat(resolver.resolve(null, "market_highest_minecraft:diamond")).isEqualTo("4.00");
    resolver.resolve(null, "market_lowest_dia$mond");
    assertThat(resolver.resolve(null, "market_lowest_dia$mond")).isEqualTo("4.00");
  }
}
