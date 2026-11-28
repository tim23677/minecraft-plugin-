package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.Order;
import dev.orderhub.gui.DeliveryMenu;
import dev.orderhub.service.OrderService;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /order sell: bester Auftrag, Vorschau im Liefermenue, Bestaetigen, Abbruch und Ausfaelle. */
class SellFlowTest extends FlowTestBase {

  private static final List<Integer> SLOTS = List.of(10, 11, 12, 13);

  private SellFlow sell;

  @BeforeEach
  void setUp() {
    sell = new SellFlow(services, new DeliveryFlow(services, SLOTS));
  }

  private long orderWithMin(long price, int quantity, int min) throws Exception {
    var request =
        new OrderService.CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            codec.encode(new ItemStack(Material.DIAMOND)),
            price,
            quantity,
            min,
            null,
            false,
            "ores",
            100,
            false);
    return h.orders.createOrder(request).orderId();
  }

  private Order orderOf(long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow());
  }

  private void holdInHand(int amount) {
    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, amount));
  }

  private String lastMessage() {
    String last = null;
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      last = PlainTextComponentSerializer.plainText().serialize(c);
    }
    return last;
  }

  @Test
  void theItemsGoToTheHighestPricedMatchingOrderAfterConfirming() throws Exception {
    long cheap = order(100, 10);
    long best = order(300, 10);
    holdInHand(16);

    sell.start(supplier, null);

    assertThat(top().getItem(10)).isNotNull();
    assertThat(top().getItem(10).getAmount()).isEqualTo(10); // nur so viel, wie der Auftrag will
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(6);
    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(best).quantityFilled()).isEqualTo(10);
    assertThat(orderOf(cheap).quantityFilled()).isZero();
    assertThat(diamondsEverywhere()).isEqualTo(16);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(h.storage.snapshot().payouts()).hasSize(1);
    assertThat(h.economy.balance(supplier.getUniqueId())).isPositive();
  }

  @Test
  void allTakesEveryMatchingItemFromTheWholeInventoryUpToTheOrderSize() throws Exception {
    long id = order(100, 50);
    holdInHand(20);
    supplier.getInventory().setItem(20, new ItemStack(Material.DIAMOND, 30));
    supplier.getInventory().setItem(21, new ItemStack(Material.DIAMOND, 10));

    sell.start(supplier, "all");
    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isEqualTo(50);
    assertThat(diamondsEverywhere()).isEqualTo(60);
  }

  @Test
  void allWithNothingElseInTheInventoryUsesTheStackInHand() throws Exception {
    long id = order(100, 50);
    holdInHand(12);

    sell.start(supplier, "ALL");
    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isEqualTo(12);
  }

  @Test
  void parseAmountKnowsAllAndRejectsNonsense() {
    assertThat(SellFlow.parseAmount("all", 5)).hasValue(Integer.MAX_VALUE);
    assertThat(SellFlow.parseAmount("allx", 5)).isEmpty();
    assertThat(SellFlow.parseAmount("0", 5)).isEmpty();
    assertThat(SellFlow.parseAmount(null, 5)).hasValue(5);
  }

  @Test
  void anAmountArgumentLimitsTheQuantity() throws Exception {
    long id = order(100, 50);
    holdInHand(40);
    sell.start(supplier, "7");
    click(DeliveryMenu.CONFIRM_SLOT);
    assertThat(orderOf(id).quantityFilled()).isEqualTo(7);
    assertThat(diamondsEverywhere()).isEqualTo(40);
  }

  @Test
  void cancellingReturnsEveryItemAndChangesNothing() throws Exception {
    long id = order(100, 10);
    holdInHand(10);
    sell.start(supplier, null);
    click(DeliveryMenu.CANCEL_SLOT);
    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(10);
    assertThat(diamondsEverywhere()).isEqualTo(10);
  }

  @Test
  void closingThePreviewReturnsEveryItem() throws Exception {
    order(100, 10);
    holdInHand(10);
    sell.start(supplier, null);
    supplier.closeInventory();
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(10);
  }

  @Test
  void aFullInventoryStillGetsEveryItemBackOnCancel() throws Exception {
    order(100, 10);
    holdInHand(10);
    for (int i = 0; i < 36; i++) {
      if (supplier.getInventory().getItem(i) == null) {
        supplier.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
      }
    }
    sell.start(supplier, null);
    supplier.closeInventory();
    assertThat(diamondsEverywhere()).isEqualTo(10);
  }

  @Test
  void aPlayerWhoDisconnectsWithThePreviewOpenLosesNothing() throws Exception {
    order(100, 10);
    holdInHand(10);
    sell.start(supplier, null);
    supplier.disconnect();
    assertThat(diamondsEverywhere()).isEqualTo(10);
  }

  @Test
  void aDatabaseOutageOpensNothingAndKeepsTheItems() throws Exception {
    order(100, 10);
    holdInHand(10);
    h.storage.setOutage(true);
    sell.start(supplier, null);
    h.storage.setOutage(false);
    assertThat(supplier.getOpenInventory().getTopInventory()).isNull();
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(10);
    assertThat(lastMessage()).contains("database error");
  }

  @Test
  void clickingConfirmSeveralTimesDeliversOnce() throws Exception {
    long id = order(100, 10);
    holdInHand(10);
    sell.start(supplier, null);
    var view = supplier.getOpenInventory();
    click(view, DeliveryMenu.CONFIRM_SLOT);
    click(view, DeliveryMenu.CONFIRM_SLOT);
    click(view, DeliveryMenu.CONFIRM_SLOT);
    assertThat(orderOf(id).quantityFilled()).isEqualTo(10);
    assertThat(h.storage.snapshot().claims()).hasSize(1);
    assertThat(diamondsEverywhere()).isEqualTo(10);
  }

  @Test
  void noOrderForTheItemSaysSo() {
    holdInHand(5);
    sell.start(supplier, null);
    assertThat(lastMessage()).contains("No open order wants this item");
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(5);
  }

  @Test
  void anOrderWhoseMinimumIsAboveWhatThePlayerHasIsSkipped() throws Exception {
    long strict = orderWithMin(500, 20, 10);
    long lenient = orderWithMin(100, 20, 1);
    holdInHand(5);
    sell.start(supplier, null);
    click(DeliveryMenu.CONFIRM_SLOT);
    assertThat(orderOf(strict).quantityFilled()).isZero();
    assertThat(orderOf(lenient).quantityFilled()).isEqualTo(5);
  }

  @Test
  void ownOrdersAreNeverATarget() throws Exception {
    order(100, 10);
    h.economy.setBalance(supplier.getUniqueId(), 1_000_000);
    h.orders.createOrder(
        new OrderService.CreateOrderRequest(
            UUID.randomUUID(),
            supplier.getUniqueId(),
            codec.encode(new ItemStack(Material.DIAMOND)),
            900,
            10,
            1,
            null,
            false,
            "ores",
            100,
            false));
    holdInHand(10);
    sell.start(supplier, null);
    click(DeliveryMenu.CONFIRM_SLOT);
    // 900 ist der eigene Auftrag und wird uebersprungen, geliefert wird an den fremden zu 100
    assertThat(h.storage.snapshot().orders().stream().filter(o -> o.quantityFilled() > 0))
        .allMatch(o -> o.owner().equals(OWNER));
  }

  @Test
  void invalidAmountsAreRejectedBeforeAnyDatabaseAccess() throws Exception {
    order(100, 10);
    holdInHand(5);
    for (String bad : new String[] {"0", "-3", "abc", "1.5", "99999999"}) {
      sell.start(supplier, bad);
      assertThat(lastMessage()).as(bad).contains("not a valid amount");
    }
    assertThat(supplier.getOpenInventory().getTopInventory()).isNull();
  }

  @Test
  void emptyHandIsExplained() {
    sell.start(supplier, null);
    assertThat(lastMessage()).contains("main hand");
  }
}
