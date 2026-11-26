package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Schliessen, Disconnect, Kick, Tod, Weltwechsel: Eingabeslot-Inhalte kommen genau einmal zurueck.
 */
class MenuLifecycleTest extends GuiTestSupport {

  private InputMenu menuWithEightDiamonds() {
    int base = total(Material.DIAMOND);
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 5), InputMenu.INPUT_A);
    place(new ItemStack(Material.DIAMOND, 3), InputMenu.INPUT_B);
    assertThat(total(Material.DIAMOND)).isEqualTo(base + 8);
    return menu;
  }

  /** Fuellt alle Slots des Spielerinventars (inklusive Ruestung und Offhand in MockBukkit). */
  private void fillInventory() {
    int size = player.getInventory().getContents().length;
    for (int i = 0; i < size; i++) {
      player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
    }
  }

  @Test
  void closeReturnsInputsToPlayerInventory() {
    InputMenu menu = menuWithEightDiamonds();
    player.closeInventory();
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.items).isEmpty();
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
    assertThat(menu.closes).containsExactly(CloseReason.PLAYER_CLOSE);
    assertThat(menu.abandons).isEmpty();
    assertThat(menu.inputContents()).isEmpty();
    assertThat(manager.current(player.getUniqueId())).isEmpty();
  }

  @Test
  void closeWithFullInventoryOverflowsToSinkWithoutLoss() {
    fillInventory();
    menuWithEightDiamonds();
    player.closeInventory();
    assertThat(inPlayerInventory(Material.DIAMOND)).isZero();
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.owners).containsOnly(player.getUniqueId());
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void partialOverflowSplitsBetweenPlayerAndSink() {
    fillInventory();
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 62)); // 2 Platz
    menuWithEightDiamonds();
    int before = total(Material.DIAMOND);
    assertThat(before).isEqualTo(70);
    player.closeInventory();
    assertThat(total(Material.DIAMOND)).isEqualTo(70);
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(64);
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(6);
  }

  @Test
  void disconnectSendsInputsToSinkNotToPlayer() {
    InputMenu menu = menuWithEightDiamonds();
    player.disconnect();
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(inPlayerInventory(Material.DIAMOND)).isZero();
    assertThat(menu.abandons).hasSize(1);
    assertThat(menu.abandons.get(0).abandoned()).isTrue();
    assertThat(menu.closes).isEmpty();
    assertThat(menu.inputContents()).isEmpty();
    assertThat(manager.openCount()).isZero();
  }

  @Test
  void kickSendsInputsToSink() {
    InputMenu menu = menuWithEightDiamonds();
    player.kick();
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(menu.abandons).isNotEmpty();
    assertThat(manager.openCount()).isZero();
  }

  @Test
  void deathSendsInputsToSinkAndNotIntoTheDroppingInventory() {
    InputMenu menu = menuWithEightDiamonds();
    PlayerDeathEvent event =
        new PlayerDeathEvent(
            player,
            DamageSource.builder(DamageType.GENERIC).build(),
            new java.util.ArrayList<>(),
            0,
            net.kyori.adventure.text.Component.text("died"),
            false);
    server.getPluginManager().callEvent(event);
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(inPlayerInventory(Material.DIAMOND)).isZero();
    assertThat(menu.abandons).containsExactly(CloseReason.DEATH);
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void closeEventWithDeathOrDisconnectReasonIsMappedToAbandon() {
    InputMenu menu = menuWithEightDiamonds();
    server
        .getPluginManager()
        .callEvent(
            new InventoryCloseEvent(player.getOpenInventory(), InventoryCloseEvent.Reason.DEATH));
    assertThat(menu.abandons).containsExactly(CloseReason.DEATH);
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void worldChangeReturnsInputsToPlayer() {
    InputMenu menu = menuWithEightDiamonds();
    World from = server.addSimpleWorld("other");
    server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, from));
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.items).isEmpty();
    assertThat(menu.closes).containsExactly(CloseReason.WORLD_CHANGE);
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void replacingAMenuReturnsTheOldInputsFirst() {
    InputMenu first = menuWithEightDiamonds();
    InputMenu second = openInputMenu();
    assertThat(first.closes).containsExactly(CloseReason.REPLACED);
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(8);
    assertThat(second.inputContents()).isEmpty();
    assertThat(manager.current(player.getUniqueId())).containsSame(second);
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void multipleEndEventsReturnItemsExactlyOnce() {
    InputMenu menu = menuWithEightDiamonds();
    player.closeInventory();
    manager.abandon(player, CloseReason.DISCONNECT);
    manager.abandon(player, CloseReason.WORLD_CHANGE);
    manager.closeAll();
    assertThat(total(Material.DIAMOND)).isEqualTo(8);
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.items).isEmpty();
    assertThat(menu.closes).hasSize(1);
    assertThat(menu.abandons).isEmpty();
  }

  @Test
  void disconnectThenCloseEventDoesNotDuplicate() {
    menuWithEightDiamonds();
    var view = player.getOpenInventory();
    player.disconnect();
    server
        .getPluginManager()
        .callEvent(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER));
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.owners).hasSize(1); // ein einziger Rueckgabeaufruf
  }

  @Test
  void failingSinkFallsBackToDroppingAtTheOnlinePlayer() {
    ReturnSink failing =
        (id, items) -> {
          throw new IllegalStateException("db down");
        };
    org.bukkit.event.HandlerList.unregisterAll(plugin);
    manager =
        new MenuManager(
            Logger.getLogger("test"),
            scheduler,
            failing,
            new MenuConfig(150),
            e -> true,
            clockNanos::get);
    server
        .getPluginManager()
        .registerEvents(new MenuListener(manager, Logger.getLogger("test")), plugin);
    fillInventory();
    menuWithEightDiamonds();
    player.closeInventory();
    int dropped =
        player.getWorld().getEntitiesByClass(Item.class).stream()
            .map(Item::getItemStack)
            .filter(i -> i.getType() == Material.DIAMOND)
            .mapToInt(ItemStack::getAmount)
            .sum();
    assertThat(dropped).isEqualTo(8);
  }

  @Test
  void hooksThrowingDoNotLoseItems() {
    Menu menu =
        new Menu(
            net.kyori.adventure.text.Component.text("x"),
            1,
            SlotPolicy.builder().input(0, i -> true).build()) {
          @Override
          protected void onClose(org.bukkit.entity.Player p, CloseReason reason) {
            throw new IllegalStateException("hook");
          }
        };
    manager.open(player, menu);
    place(new ItemStack(Material.DIAMOND, 2), 0);
    player.closeInventory();
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(2);
  }

  @Test
  void openForOfflinePlayerDoesNothingAndLeavesNoMenu() {
    var other = server.addPlayer("gone");
    other.disconnect();
    List<ItemStack> none = List.of();
    manager.open(other, new InputMenu());
    assertThat(manager.openCount()).isZero();
    assertThat(none).isEmpty();
  }

  @Test
  void openRunsInEntityContext() {
    int before = scheduler.entityTasks;
    openInputMenu();
    assertThat(scheduler.entityTasks).isEqualTo(before + 1);
  }
}
