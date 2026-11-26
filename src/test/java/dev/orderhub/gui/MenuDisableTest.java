package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.scheduler.PluginScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Plugin-Disable (insbesondere Folia): erst entschaerfen, dann verschieben, fail closed. */
class MenuDisableTest extends GuiTestSupport {

  /** Fuehrt Tasks aus, bis {@link #kill()}: danach verhaelt es sich wie Folia nach dem Disable. */
  static final class DyingScheduler implements PluginScheduler {
    private final DirectScheduler delegate = new DirectScheduler();
    private boolean dead;
    int rejected;

    void kill() {
      dead = true;
    }

    @Override
    public void runAt(Location location, Runnable task) {
      if (dead) {
        throw new IllegalStateException("plugin disabled");
      }
      delegate.runAt(location, task);
    }

    @Override
    public void runOn(Entity entity, Runnable task) {
      if (dead) {
        throw new IllegalStateException("plugin disabled");
      }
      delegate.runOn(entity, task);
    }

    @Override
    public boolean tryRunOn(Entity entity, Runnable task) {
      if (dead) {
        rejected++;
        return false;
      }
      delegate.runOn(entity, task);
      return true;
    }

    @Override
    public void runGlobal(Runnable task) {}

    @Override
    public void runAsync(Runnable task) {}

    @Override
    public void cancelAll() {}
  }

  private InputMenu openFor(PlayerMock who, int diamondsA, int diamondsB) {
    InputMenu menu = new InputMenu();
    manager.open(who, menu);
    if (diamondsA > 0) {
      menu.getInventory().setItem(InputMenu.INPUT_A, new ItemStack(Material.DIAMOND, diamondsA));
    }
    if (diamondsB > 0) {
      menu.getInventory().setItem(InputMenu.INPUT_B, new ItemStack(Material.DIAMOND, diamondsB));
    }
    return menu;
  }

  private int countAll(Material material, PlayerMock... players) {
    int sum = sink.count(material);
    for (PlayerMock p : players) {
      sum +=
          count(p.getInventory(), material)
              + (p.getItemOnCursor().getType() == material ? p.getItemOnCursor().getAmount() : 0);
    }
    return sum;
  }

  private void assertDisarmed(InputMenu menu) {
    for (ItemStack item : menu.getInventory().getContents()) {
      assertThat(item == null || item.isEmpty()).as("slot content %s", item).isTrue();
    }
    assertThat(menu.isClosed()).isTrue();
  }

  @Test
  void disableWithWorkingSchedulerReturnsAllItemsAndNeverLeaksButtonItems() {
    PlayerMock second = server.addPlayer("second");
    InputMenu m1 = openFor(player, 5, 3);
    InputMenu m2 = openFor(second, 7, 0);
    int diamondsBefore = 15;
    manager.closeAll();
    assertThat(manager.isDisabled()).isTrue();
    assertThat(manager.openCount()).isZero();
    assertThat(countAll(Material.DIAMOND, player, second)).isEqualTo(diamondsBefore);
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(diamondsBefore);
    assertThat(count(player.getInventory(), Material.PAPER)).isZero();
    assertThat(count(second.getInventory(), Material.PAPER)).isZero();
    assertDisarmed(m1);
    assertDisarmed(m2);
    assertThat(m1.abandons).containsExactly(CloseReason.PLUGIN_DISABLE);
  }

  @Test
  void disableWithDeadEntitySchedulerStillSecuresEverything() {
    DyingScheduler dying = new DyingScheduler();
    rebuild(dying, e -> false); // Spieler liegen nicht im aktuellen Kontext (Folia global thread)
    PlayerMock second = server.addPlayer("second");
    InputMenu a = openFor(player, 5, 3);
    InputMenu b = openFor(second, 7, 0);
    dying.kill();
    manager.closeAll();
    assertThat(dying.rejected).isEqualTo(2);
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(15);
    assertThat(countAll(Material.DIAMOND, player, second)).isEqualTo(15);
    assertThat(count(player.getInventory(), Material.PAPER)).isZero();
    assertThat(count(second.getInventory(), Material.PAPER)).isZero();
    assertDisarmed(a);
    assertDisarmed(b);
    assertThat(a.abandons).containsExactly(CloseReason.PLUGIN_DISABLE);
  }

  @Test
  void viewsLeftOpenAfterDeadSchedulerOnlyShowAnEmptyInventoryAndStayLocked() {
    DyingScheduler dying = new DyingScheduler();
    rebuild(dying, e -> false);
    InputMenu menu = openFor(player, 5, 3);
    var view = player.getOpenInventory();
    dying.kill();
    manager.closeAll();
    assertThat(player.getOpenInventory().getTopInventory().getHolder()).isSameAs(menu);
    // Der Spieler versucht, ein (frueheres) Button-Item oder Eingabe-Item herauszuholen.
    for (int slot : new int[] {InputMenu.BUTTON, InputMenu.INPUT_A}) {
      InventoryClickEvent event =
          new InventoryClickEvent(
              view,
              org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
              slot,
              ClickType.LEFT,
              InventoryAction.PICKUP_ALL);
      server.getPluginManager().callEvent(event);
      assertThat(event.isCancelled()).isTrue();
      assertThat(menu.getInventory().getItem(slot)).isNull();
    }
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
  }

  @Test
  void listenerStaysFailClosedAfterDisable() {
    InputMenu menu = openFor(player, 2, 0);
    var view = player.getOpenInventory();
    manager.closeAll();
    for (int slot : new int[] {InputMenu.BUTTON, InputMenu.INPUT_A, 27 + 3}) {
      InventoryClickEvent event =
          new InventoryClickEvent(
              view,
              org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
              slot,
              ClickType.LEFT,
              InventoryAction.PICKUP_ALL);
      server.getPluginManager().callEvent(event);
      assertThat(event.isCancelled()).as("slot %d", slot).isTrue();
    }
    assertThat(menu.buttonClicks).isZero();
    assertThat(count(player.getInventory(), Material.PAPER)).isZero();
  }

  @Test
  void openAfterDisableIsRefused() {
    manager.closeAll();
    InputMenu menu = new InputMenu();
    manager.open(player, menu);
    assertThat(manager.openCount()).isZero();
    assertThat(menu.isClosed()).isTrue();
  }

  @Test
  void closeAllIsIdempotent() {
    openFor(player, 4, 4);
    manager.closeAll();
    manager.closeAll();
    assertThat(sink.count(Material.DIAMOND)).isEqualTo(8);
    assertThat(sink.items).hasSize(2);
  }

  @Test
  void disableWithoutOpenMenusIsHarmless() {
    manager.closeAll();
    assertThat(sink.items).isEmpty();
    assertThat(manager.isDisabled()).isTrue();
  }

  @Test
  void pagedMenuAndConfirmMenuAreDisarmedToo() {
    var confirm =
        new ConfirmMenu(
            net.kyori.adventure.text.Component.text("c"),
            new ItemStack(Material.LIME_WOOL),
            new ItemStack(Material.RED_WOOL),
            p -> {},
            id -> {});
    manager.open(player, confirm);
    manager.closeAll();
    assertThat(confirm.isClosed()).isTrue();
    assertThat(confirm.isDecided()).isTrue();
    assertThat(count(player.getInventory(), Material.LIME_WOOL)).isZero();
  }
}
