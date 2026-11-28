package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.gui.DeliveryMenu;
import dev.orderhub.item.MatchPolicy;
import dev.orderhub.item.RejectionReason;
import dev.orderhub.service.OrderService;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Lieferung gegen einen Auftrag mit Toleranz. allow-damaged laesst beschaedigte Items zu, ob sie
 * passen, entscheidet die Toleranz des Auftrags.
 */
class DeliveryToleranceFlowTest extends FlowTestBase {

  private static final List<Integer> SLOTS = List.of(10, 11, 12, 13);

  @Override
  protected MatchPolicy matchPolicy() {
    return MatchPolicy.strict().allowing(RejectionReason.DAMAGED);
  }

  private long swordOrder(OrderOptions options) throws Exception {
    ItemSnapshot snapshot = codec.encode(new ItemStack(Material.DIAMOND_SWORD));
    var result =
        h.orders.createOrder(
            new OrderService.CreateOrderRequest(
                UUID.randomUUID(),
                OWNER,
                snapshot,
                100,
                1,
                1,
                null,
                false,
                "tools",
                100,
                false,
                OrderService.CreateOrderRequest.FEE_UNCHECKED,
                options));
    assertThat(result.success()).isTrue();
    return result.orderId();
  }

  private Order orderOf(long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow());
  }

  private void deliver(long id, ItemStack stack) {
    new DeliveryFlow(services, SLOTS).open(supplier, id);
    put(10, stack);
    click(DeliveryMenu.CONFIRM_SLOT);
  }

  // Grenze von MockBukkit: Inventare tragen Datenkomponenten (z. B. Schaden) nicht, ein im Menue
  // liegendes Schwert ist dort immer neu. Dass Schaden und Verzauberungen je Toleranz richtig
  // verglichen werden, belegen ItemMatcherToleranceTest und das Spieler-Szenario auf dem echten
  // Server. Hier wird geprueft, dass der Ablauf die Optionen des Auftrags liest und anwendet.

  @Test
  void theFlowMapsTheStoredOptionsToTheMatcherTolerance() {
    assertThat(DeliveryFlow.toleranceOf(new OrderOptions(60, true, false)))
        .isEqualTo(new dev.orderhub.item.Tolerance(60, true));
    assertThat(DeliveryFlow.toleranceOf(OrderOptions.EXACT))
        .isEqualTo(dev.orderhub.item.Tolerance.EXACT);
  }

  @Test
  void anOrderWithToleranceAcceptsANewItemAndPaysOut() throws Exception {
    long id = swordOrder(new OrderOptions(50, true, false));
    deliver(id, new ItemStack(Material.DIAMOND_SWORD));
    assertThat(orderOf(id).quantityFilled()).isEqualTo(1);
    assertThat(h.storage.snapshot().claims()).hasSize(1);
  }

  @Test
  void anExactOrderAcceptsANewSword() throws Exception {
    long id = swordOrder(OrderOptions.EXACT);
    deliver(id, new ItemStack(Material.DIAMOND_SWORD));
    assertThat(orderOf(id).quantityFilled()).isEqualTo(1);
  }

  @Test
  void aDifferentMaterialIsNeverAcceptedWhateverTheTolerance() throws Exception {
    long id = swordOrder(new OrderOptions(0, true, false));
    deliver(id, new ItemStack(Material.IRON_SWORD));
    assertThat(orderOf(id).quantityFilled()).isZero();
  }

  private org.mockbukkit.mockbukkit.entity.PlayerMock onlineOwner() {
    var owner = new org.mockbukkit.mockbukkit.entity.PlayerMock(server, "owner", OWNER);
    server.addPlayer(owner);
    return owner;
  }

  private int swords(org.bukkit.entity.Player player) {
    int n = 0;
    for (ItemStack stack :
        java.util.Objects.requireNonNull(player.getInventory().getStorageContents())) {
      if (stack != null && stack.getType() == Material.DIAMOND_SWORD) {
        n += stack.getAmount();
      }
    }
    return n;
  }

  @Test
  void autoClaimHandsTheDeliveredItemToAnOnlineOwner() throws Exception {
    var owner = onlineOwner();
    long id = swordOrder(new OrderOptions(100, false, true));

    deliver(id, new ItemStack(Material.DIAMOND_SWORD));

    assertThat(swords(owner)).isEqualTo(1);
    assertThat(h.storage.snapshot().claims()).allMatch(c -> c.quantity() == 0);
  }

  @Test
  void withoutTheFlagTheItemWaitsAsAParcel() throws Exception {
    var owner = onlineOwner();
    long id = swordOrder(OrderOptions.EXACT);

    deliver(id, new ItemStack(Material.DIAMOND_SWORD));

    assertThat(swords(owner)).isZero();
    assertThat(h.storage.snapshot().claims()).hasSize(1);
  }

  @Test
  void autoClaimWithAnOfflineOwnerKeepsTheParcel() throws Exception {
    long id = swordOrder(new OrderOptions(100, false, true));

    deliver(id, new ItemStack(Material.DIAMOND_SWORD));

    assertThat(h.storage.snapshot().claims()).hasSize(1);
  }

  @Test
  void autoClaimWithAFullInventoryLeavesTheRestInTheParcel() throws Exception {
    var owner = onlineOwner();
    for (int i = 0; i < 36; i++) {
      owner.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
    }
    long id = swordOrder(new OrderOptions(100, false, true));

    deliver(id, new ItemStack(Material.DIAMOND_SWORD));

    assertThat(swords(owner)).isZero();
    assertThat(h.storage.snapshot().claims()).hasSize(1);
  }
}
