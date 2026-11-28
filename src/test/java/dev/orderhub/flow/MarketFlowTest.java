package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderQuery;
import dev.orderhub.gui.MenuLayout;
import dev.orderhub.service.OrderService;
import dev.orderhub.testsupport.Harness;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Marktplatz: Seiten per Keyset, Kategorie, Sortierung, Suche, leere Treffer, Ausfall, Abgang. */
class MarketFlowTest extends FlowTestBase {

  private MarketFlow flow;

  @BeforeEach
  void setUp() {
    ChatInput chat = new ChatInput(scheduler, () -> "cancel", nanos::incrementAndGet);
    DeliveryFlow delivery = new DeliveryFlow(services, java.util.List.of(10, 11));
    Menus menusView =
        new Menus(services, delivery, new ParcelFlow(services, ParcelFlow.Kind.CLAIM));
    flow =
        new MarketFlow(
            services, chat, delivery, menusView::orderIcon, () -> MenuLayout.defaults(6));
  }

  private void create(String key, String category, long price) {
    var result =
        h.orders.createOrder(
            new OrderService.CreateOrderRequest(
                UUID.randomUUID(),
                OWNER,
                Harness.item(key),
                price,
                1,
                1,
                null,
                false,
                category,
                1000,
                false));
    assertThat(result.success()).isTrue();
  }

  private int filled() {
    int n = 0;
    for (int slot = 0; slot < 45; slot++) {
      if (top().getItem(slot) != null) {
        n++;
      }
    }
    return n;
  }

  private String loreOf(int slot) {
    ItemStack item = top().getItem(slot);
    var lore = item == null ? null : item.lore();
    if (lore == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    lore.forEach(
        line -> text.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n'));
    return text.toString();
  }

  private void seed60() {
    for (int i = 1; i <= 60; i++) {
      create(
          i % 2 == 0 ? "minecraft:diamond_sword" : "minecraft:iron_ingot",
          i % 2 == 0 ? "tools" : "ores",
          i * 100L);
    }
  }

  private static final int PREVIOUS = 45;
  private static final int CATEGORY = 47;
  private static final int SORT = 49;
  private static final int NEXT = 53;

  @Test
  void pagesAreLoadedByKeysetAndNavigateForwardAndBack() {
    seed60();
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.HIGHEST_PRICE, null, null));
    assertThat(filled()).isEqualTo(45);
    assertThat(top().getItem(NEXT)).isNotNull();
    assertThat(top().getItem(PREVIOUS)).isNull();
    assertThat(loreOf(0)).startsWith("Price each: 60.00");

    click(NEXT);
    assertThat(filled()).isEqualTo(15);
    assertThat(top().getItem(NEXT)).isNull();
    assertThat(top().getItem(PREVIOUS)).isNotNull();
    assertThat(loreOf(0)).contains("15.00");

    click(PREVIOUS);
    assertThat(filled()).isEqualTo(45);
    assertThat(loreOf(0)).startsWith("Price each: 60.00");
    assertThat(top().getItem(PREVIOUS)).isNull();
  }

  @Test
  void anotherSortOrderStartsOnTheFirstPageAgain() {
    seed60();
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.HIGHEST_PRICE, null, null));
    click(NEXT);
    click(SORT); // HIGHEST_PRICE -> LOWEST_PRICE
    assertThat(filled()).isEqualTo(45);
    assertThat(top().getItem(PREVIOUS)).isNull();
    assertThat(loreOf(0)).contains("1.00");
  }

  @Test
  void theCategoryButtonCyclesThroughCategoriesAndFilters() {
    seed60();
    flow.open(supplier, MarketFlow.Filter.all());
    assertThat(filled()).isEqualTo(45);
    click(CATEGORY); // all -> ores
    assertThat(filled()).isEqualTo(30);
    click(CATEGORY); // ores -> blocks
    assertThat(filled()).isZero();
    for (int i = 0; i < 2; i++) { // blocks -> farm -> mob-drops
      click(CATEGORY);
    }
    click(CATEGORY); // -> tools
    assertThat(filled()).isEqualTo(30);
  }

  @Test
  void textSearchFindsOnlyMatchingItems() {
    seed60();
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, "Diamond Sword"));
    assertThat(filled()).isEqualTo(30);
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, "iron"));
    assertThat(filled()).isEqualTo(30);
  }

  @Test
  void aFourthTextSearchWithinTheWindowIsRefusedAndLeavesTheMenuAlone() {
    seed60();
    for (int i = 0; i < SearchThrottle.LIMIT; i++) {
      flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, "iron"));
    }
    supplier.closeInventory();
    while (supplier.nextComponentMessage() != null) {
      // Meldungen der erlaubten Suchen verwerfen
    }

    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, "iron"));

    var message = supplier.nextComponentMessage();
    assertThat(PlainTextComponentSerializer.plainText().serialize(message))
        .contains("search too fast");
  }

  @Test
  void searchesWithoutTextAreNeverThrottled() {
    seed60();
    for (int i = 0; i < SearchThrottle.LIMIT + 3; i++) {
      flow.open(supplier, MarketFlow.Filter.all());
      assertThat(filled()).isEqualTo(45);
    }
  }

  @Test
  void anEmptyResultTellsThePlayerInsteadOfOpeningAnEmptyConfusion() {
    seed60();
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.NEWEST, null, "netherite"));
    assertThat(filled()).isZero();
    var message = supplier.nextComponentMessage();
    assertThat(PlainTextComponentSerializer.plainText().serialize(message))
        .contains("No open orders");
  }

  @Test
  void aDatabaseOutageTellsThePlayerAndOpensNothing() {
    seed60();
    h.storage.setOutage(true);
    flow.open(supplier, MarketFlow.Filter.all());
    h.storage.setOutage(false);
    assertThat(supplier.getOpenInventory().getTopInventory()).isNull();
    var message = supplier.nextComponentMessage();
    assertThat(PlainTextComponentSerializer.plainText().serialize(message))
        .contains("database error");
  }

  @Test
  void aPlayerWhoLeavesWhileThePageLoadsCausesNoError() {
    seed60();
    scheduler.deferAsync = true;
    flow.open(supplier, MarketFlow.Filter.all());
    supplier.disconnect();
    scheduler.drain();
    assertThat(supplier.isOnline()).isFalse();
  }

  @Test
  void repeatedNextClicksNeverSkipOrDuplicateAPage() {
    seed60();
    flow.open(supplier, new MarketFlow.Filter(OrderQuery.Sort.LOWEST_PRICE, null, null));
    click(NEXT);
    click(NEXT); // der Button ist auf der letzten Seite weg, der Klick bleibt wirkungslos
    assertThat(filled()).isEqualTo(15);
    assertThat(loreOf(0)).contains("46.00");
  }
}
