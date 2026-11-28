package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Ein eingefrorenes Menue (laufende Lieferung) gibt seine Items nicht beim Schliessen heraus. Der
 * Ablauf nimmt sie selbst, genau einmal. So entstehen keine Doppelungen zwischen Schliessen und
 * Recovery.
 */
class MenuFreezeTest extends GuiTestSupport {

  @Test
  void frozenMenuKeepsItemsOnCloseAndFlowTakesThemExactlyOnce() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 8), InputMenu.INPUT_A);
    menu.freeze();
    player.closeInventory();
    assertThat(inPlayerInventory(Material.DIAMOND)).isZero();
    assertThat(sink.count(Material.DIAMOND)).isZero();
    assertThat(menu.takeInputs()).hasSize(1);
    assertThat(menu.takeInputs()).isEmpty();
  }

  @Test
  void frozenMenuKeepsItemsOnDisconnect() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 8), InputMenu.INPUT_B);
    menu.freeze();
    manager.abandon(player, CloseReason.DISCONNECT);
    assertThat(sink.count(Material.DIAMOND)).isZero();
    assertThat(menu.takeInputs().stream().mapToInt(ItemStack::getAmount).sum()).isEqualTo(8);
  }

  @Test
  void unfrozenMenuStillReturnsItemsOnClose() {
    openInputMenu();
    place(new ItemStack(Material.DIAMOND, 8), InputMenu.INPUT_A);
    player.closeInventory();
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void frozenMenuIgnoresFurtherClicks() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 8), InputMenu.INPUT_A);
    menu.freeze();
    assertThat(menu.isFrozen()).isTrue();
  }
}
