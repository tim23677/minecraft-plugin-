package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OrderHubMenuTest {

  private ServerMock server;
  private PlayerMock player;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    MockBukkit.load(OrderHubPlugin.class);
    player = server.addPlayer();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void menuHasExpectedSizeAndHolder() {
    var menu = new OrderHubMenu();
    assertThat(menu.getInventory().getSize()).isEqualTo(OrderHubMenu.SIZE);
    assertThat(menu.getInventory().getHolder()).isSameAs(menu);
  }

  @Test
  void clickInMenuIsCancelled() {
    var menu = new OrderHubMenu();
    menu.open(player);
    var view = player.getOpenInventory();
    var event =
        new InventoryClickEvent(
            view,
            SlotType.CONTAINER,
            OrderHubMenu.REFRESH_SLOT,
            ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isTrue();
  }

  @Test
  void clickInForeignInventoryIsNotTouched() {
    var foreign = server.createInventory(null, 9);
    player.openInventory(foreign);
    foreign.setItem(0, new ItemStack(Material.STONE));
    var event =
        new InventoryClickEvent(
            player.getOpenInventory(),
            SlotType.CONTAINER,
            0,
            ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isFalse();
  }
}
