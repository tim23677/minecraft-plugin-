package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Statistik, Favoriten und Marktanalyse als Menues (Daten aus einer echten SQLite-Datenbank). */
class InsightMenusTest extends FlowTestBase {

  private JdbcStorage insightStorage;
  private InsightsService insights;
  private InsightMenus menusUnderTest;

  @BeforeEach
  void setUp() throws Exception {
    insightStorage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("insights.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    DiscordService discord =
        new DiscordService(
            insightStorage,
            () -> new DiscordService.Config(false, "OrderHub", Set.of(), 1_000_000),
            Logger.getLogger("test"));
    insights = new InsightsService(insightStorage, discord, () -> 60, () -> 3);
    Services base = services;
    Services withInsights =
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
            base.discord());
    menusUnderTest = new InsightMenus(withInsights);
  }

  @AfterEach
  void close() {
    insightStorage.close();
  }

  private String lastMessage() {
    String last = null;
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      last = PlainTextComponentSerializer.plainText().serialize(c);
    }
    return last;
  }

  private static String plain(net.kyori.adventure.text.Component c) {
    return PlainTextComponentSerializer.plainText().serialize(c);
  }

  @Test
  void statsWithoutTradesSaysSoInsteadOfOpeningAnEmptyMenu() {
    menusUnderTest.stats(supplier);
    assertThat(lastMessage()).contains("No trades yet");
    assertThat(supplier.getOpenInventory().getTopInventory()).isNull();
  }

  @Test
  void favoritesListsEntriesAndRightClickRemovesOne() {
    insights.addFavorite(supplier.getUniqueId(), "minecraft:diamond");
    insights.addFavorite(supplier.getUniqueId(), "minecraft:iron_ingot");
    menusUnderTest.favorites(supplier);
    assertThat(top().getItem(0)).isNotNull();
    assertThat(top().getItem(1)).isNotNull();
    assertThat(top().getItem(2)).isNull();
    String removedKey =
        plain(java.util.Objects.requireNonNull(top().getItem(0)).displayName())
            .replaceAll("[\\[\\]]", "");
    assertThat(removedKey).startsWith("minecraft:");

    var view = supplier.getOpenInventory();
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.inventory.InventoryClickEvent(
                view,
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                0,
                org.bukkit.event.inventory.ClickType.RIGHT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_HALF));

    assertThat(insights.favorites(supplier.getUniqueId()))
        .hasSize(1)
        .noneMatch(f -> f.itemKey().equals(removedKey));
  }

  @Test
  void noFavoritesGivesAMessage() {
    menusUnderTest.favorites(supplier);
    assertThat(lastMessage()).contains("No favorites");
  }

  @Test
  void analyticsShowsDemandTradesAndAHintWhenThereIsNoData() {
    menusUnderTest.analytics(supplier, "minecraft:diamond");
    ItemStack icon = top().getItem(13);
    assertThat(icon).isNotNull();
    StringBuilder lore = new StringBuilder();
    java.util.Objects.requireNonNull(icon.lore())
        .forEach(line -> lore.append(plain(line)).append('\n'));
    assertThat(lore.toString())
        .contains("No open orders for this item")
        .contains("no completed trades")
        .contains("Not enough market data");
  }

  @Test
  void aDatabaseOutageIsReportedAndNothingOpens() {
    insightStorage.close();
    menusUnderTest.stats(supplier);
    assertThat(lastMessage()).contains("database error");
    assertThat(supplier.getOpenInventory().getTopInventory()).isNull();
  }

  @Test
  void aPlayerWhoLeavesWhileTheDataLoadsCausesNoError() {
    scheduler.deferAsync = true;
    menusUnderTest.analytics(supplier, "minecraft:diamond");
    supplier.disconnect();
    scheduler.drain();
    assertThat(supplier.isOnline()).isFalse();
  }

  @Test
  void analyticsShowsAPriceTrendLineWhenTradesExist() {
    long now = System.currentTimeMillis();
    insightStorage
        .transact(uow ->
            uow.insights()
                .recordTrade(java.util.UUID.randomUUID(), 1, "minecraft:diamond", 500, 2, now))
        .join();

    menusUnderTest.analytics(supplier, "minecraft:diamond");

    StringBuilder lore = new StringBuilder();
    java.util.Objects.requireNonNull(top().getItem(13).lore())
        .forEach(line -> lore.append(plain(line)).append('\n'));
    assertThat(lore.toString()).contains("Price trend, 7 days:").contains("5.00 to 5.00");
  }
}
