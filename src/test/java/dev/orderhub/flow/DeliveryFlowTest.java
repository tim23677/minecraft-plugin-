package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.Order;
import dev.orderhub.gui.DeliveryMenu;
import dev.orderhub.storage.HoldRepository;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.testsupport.InMemoryStorage;
import dev.orderhub.tx.TxState;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Dupe-Pfade der Lieferung. Jede Pruefung endet in der Invariante "Items vorher = Items nachher".
 */
class DeliveryFlowTest extends FlowTestBase {

  private static final List<Integer> SLOTS = List.of(10, 11, 12, 13);

  private DeliveryFlow flow() {
    return new DeliveryFlow(services, SLOTS);
  }

  private Order orderOf(long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow());
  }

  private void give(int amount) {
    supplier.getInventory().addItem(new ItemStack(Material.DIAMOND, amount));
  }

  /** Nimmt Items aus dem Inventar und legt sie ins Liefermenue (wie ein Spieler). */
  private void stage(int slot, int amount) {
    supplier.getInventory().removeItem(new ItemStack(Material.DIAMOND, amount));
    put(slot, new ItemStack(Material.DIAMOND, amount));
  }

  @Test
  void deliveryWithSurplusKeepsEveryItem() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(16);
    stage(10, 16);

    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isEqualTo(10);
    assertThat(h.storage.snapshot().claims())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.quantity()).isEqualTo(10);
          assertThat(p.owner()).isEqualTo(OWNER);
        });
    assertThat(h.storage.snapshot().returns())
        .as(
            "returns "
                + h.storage.snapshot().returns()
                + " journal "
                + h.storage.snapshot().journal().stream()
                    .map(j -> j.type() + ":" + j.state() + ":" + j.detail())
                    .toList())
        .hasSize(1);
    java.util.List<String> msgs = new java.util.ArrayList<>();
    net.kyori.adventure.text.Component m;
    while ((m = supplier.nextComponentMessage()) != null) {
      msgs.add(
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(m));
    }
    assertThat(h.storage.snapshot().returns().get(0).quantity()).as("msgs " + msgs).isZero();
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(6); // Ueberschuss zurueck
    assertThat(diamondsEverywhere()).isEqualTo(16);
    assertThat(h.storage.holds()).allMatch(x -> x.state() == HoldRepository.State.CONSUMED);
  }

  @Test
  void cancelButtonReturnsTheItems() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(5);
    stage(10, 5);

    click(DeliveryMenu.CANCEL_SLOT);

    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(5);
    assertThat(diamondsEverywhere()).isEqualTo(5);
    assertThat(
            h.storage.snapshot().journal().stream()
                .filter(j -> j.type() == dev.orderhub.tx.TxType.DELIVERY))
        .isEmpty();
  }

  @Test
  void closingTheMenuReturnsTheItems() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(5);
    stage(11, 5);

    supplier.closeInventory();

    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(diamondsEverywhere()).isEqualTo(5);
  }

  @Test
  void disconnectWithItemsInTheMenuStoresThemAsAReturn() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(5);
    stage(10, 5);

    supplier.disconnect();

    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.quantity()).isEqualTo(5);
          assertThat(p.owner()).isEqualTo(supplier.getUniqueId());
        });
    assertThat(diamondsEverywhere()).isEqualTo(5);
  }

  @Test
  void doubleClickOnConfirmDeliversOnlyOnce() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(4);
    stage(10, 4);

    scheduler.deferAsync = true; // die Datenbankarbeit des ersten Klicks laeuft noch
    click(DeliveryMenu.CONFIRM_SLOT);
    click(DeliveryMenu.CONFIRM_SLOT);
    click(DeliveryMenu.CONFIRM_SLOT);
    scheduler.drain();

    assertThat(orderOf(id).quantityFilled()).isEqualTo(4);
    assertThat(
            h.storage.snapshot().journal().stream()
                .filter(j -> j.type() == dev.orderhub.tx.TxType.DELIVERY))
        .hasSize(1);
    assertThat(diamondsEverywhere()).isEqualTo(4);
  }

  @Test
  void aFullInventoryKeepsTheSurplusAsAReturnWithoutLoss() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(16);
    stage(10, 16);
    // Inventar komplett fuellen, damit der Ueberschuss nicht hineinpasst
    for (int i = 0; i < 36; i++) {
      if (supplier.getInventory().getItem(i) == null) {
        supplier.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
      }
    }

    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isEqualTo(10);
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(p -> assertThat(p.quantity()).isEqualTo(6));
    assertThat(diamondsEverywhere()).isEqualTo(16);
  }

  @Test
  void aDatabaseOutageDuringPrepareNeverDuplicatesAndNeverDropsTheItems() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(5);
    stage(10, 5);

    h.storage.setOutage(true);
    click(DeliveryMenu.CONFIRM_SLOT);
    // Fail closed: nichts wurde zurueckgegeben, nichts doppelt. Die Items liegen noch im Menue.
    assertThat(inInventory(supplier, Material.DIAMOND)).isZero();
    assertThat(diamondsEverywhere()).isEqualTo(5);

    h.storage.setOutage(false);
    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(
            h.storage.snapshot().journal().stream()
                .filter(j -> j.type() == dev.orderhub.tx.TxType.DELIVERY))
        .isEmpty();
  }

  @Test
  void aLostAcknowledgeOfPrepareLeavesTheItemsToTheReservation() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(5);
    stage(10, 5);

    // prepare committet, die Antwort geht verloren
    h.storage.failTransaction(1, InMemoryStorage.FailAt.AFTER_COMMIT);
    click(DeliveryMenu.CONFIRM_SLOT);

    // Der Flow darf nicht zurueckgeben: Reservierung und Recovery besitzen die Items.
    assertThat(inInventory(supplier, Material.DIAMOND)).isZero();
    assertThat(h.storage.holds())
        .singleElement()
        .satisfies(x -> assertThat(x.quantity()).isEqualTo(5));

    h.restart();
    h.recovery.sweep();
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(p -> assertThat(p.quantity()).isEqualTo(5));
    assertThat(diamondsEverywhere()).isEqualTo(5);
  }

  @Test
  void anEconomyFailureOnPayoutDoesNotAffectTheItemsOrTheOrder() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(4);
    stage(10, 4);
    h.economy.failNext(Op.DEPOSIT, Mode.THROW_AFTER);

    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isEqualTo(4);
    assertThat(h.storage.snapshot().claims())
        .singleElement()
        .satisfies(p -> assertThat(p.quantity()).isEqualTo(4));
    assertThat(h.storage.snapshot().journal())
        .anyMatch(j -> j.state() == TxState.MANUAL_REVIEW); // unklare Zahlung, nie wiederholt
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(diamondsEverywhere()).isEqualTo(4);
  }

  @Test
  void deliveringToAnOrderThatWasCancelledMeanwhileReturnsEverything() throws Exception {
    long id = order(100, 10);
    flow().open(supplier, id);
    give(4);
    stage(10, 4);
    h.orders.cancelOrder(id, OWNER, false);

    click(DeliveryMenu.CONFIRM_SLOT);

    assertThat(orderOf(id).quantityFilled()).isZero();
    assertThat(h.storage.snapshot().claims()).isEmpty();
    assertThat(diamondsEverywhere()).isEqualTo(4);
  }
}
