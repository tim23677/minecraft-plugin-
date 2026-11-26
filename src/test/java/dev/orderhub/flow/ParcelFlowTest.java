package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Abholung von Claims und Rueckgaben: voller Inventar, Wiederholung, Spieler weg. */
class ParcelFlowTest extends FlowTestBase {

  private void addReturn(UUID owner, int quantity) throws Exception {
    var snapshot = codec.encode(new ItemStack(Material.DIAMOND));
    h.storage
        .transact(uow -> uow.returns().add(owner, null, snapshot, quantity, UUID.randomUUID(), 1L))
        .join();
  }

  private int available() {
    return h.storage.snapshot().returns().stream()
        .filter(p -> p.state() == dev.orderhub.storage.ParcelRepository.State.AVAILABLE)
        .mapToInt(dev.orderhub.storage.ParcelRepository.Parcel::quantity)
        .sum();
  }

  @Test
  void claimingMovesTheItemsOnceAndASecondClaimFindsNothing() throws Exception {
    addReturn(supplier.getUniqueId(), 10);
    ParcelFlow flow = new ParcelFlow(services, ParcelFlow.Kind.RETURN);

    flow.claimAll(supplier);
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(10);
    assertThat(available()).isZero();

    flow.claimAll(supplier);
    assertThat(inInventory(supplier, Material.DIAMOND)).isEqualTo(10);
    assertThat(h.storage.snapshot().returns().stream().mapToInt(x -> x.quantity()).sum()).isZero();
  }

  @Test
  void aFullInventoryLeavesTheParcelInStorage() throws Exception {
    addReturn(supplier.getUniqueId(), 10);
    for (int i = 0; i < 36; i++) {
      supplier.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
    }

    new ParcelFlow(services, ParcelFlow.Kind.RETURN).claimAll(supplier);

    assertThat(inInventory(supplier, Material.DIAMOND)).isZero();
    assertThat(available()).isEqualTo(10);
  }

  @Test
  void aPartiallyFullInventoryMovesOnlyWhatFitsAndKeepsTheRest() throws Exception {
    addReturn(supplier.getUniqueId(), 100);
    for (int i = 0; i < 35; i++) {
      supplier.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
    }

    new ParcelFlow(services, ParcelFlow.Kind.RETURN).claimAll(supplier);

    int moved = inInventory(supplier, Material.DIAMOND);
    assertThat(moved).isBetween(1, 64);
    assertThat(moved + available()).isEqualTo(100);
  }

  @Test
  void aParcelOfAnotherPlayerIsNeverHandedOut() throws Exception {
    addReturn(UUID.randomUUID(), 10);
    new ParcelFlow(services, ParcelFlow.Kind.RETURN).claimAll(supplier);
    assertThat(inInventory(supplier, Material.DIAMOND)).isZero();
    assertThat(available()).isEqualTo(10);
  }
}
