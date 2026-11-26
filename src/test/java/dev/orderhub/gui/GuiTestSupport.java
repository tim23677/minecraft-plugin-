package dev.orderhub.gui;

import dev.orderhub.scheduler.PluginScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Gemeinsame Testumgebung: MockBukkit, Listener, Manager mit sofort ausfuehrendem Scheduler,
 * aufzeichnende Rueckgabesenke und steuerbare Uhr. MockBukkit fuehrt Klicks nicht selbst aus; die
 * Hilfsmethoden wenden erlaubte Klicks minimal an (nur wenn das Event nicht abgebrochen ist).
 */
abstract class GuiTestSupport {

  /** Fuehrt Tasks sofort im aufrufenden Thread aus. */
  static final class DirectScheduler implements PluginScheduler {
    int entityTasks;

    @Override
    public void runAt(Location location, Runnable task) {
      task.run();
    }

    @Override
    public void runOn(Entity entity, Runnable task) {
      entityTasks++;
      task.run();
    }

    @Override
    public void runGlobal(Runnable task) {
      task.run();
    }

    @Override
    public void runAsync(Runnable task) {
      task.run();
    }

    @Override
    public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {}

    @Override
    public void cancelAll() {}
  }

  /** Menue mit zwei Eingabeslots (nur Diamanten) und einem Zaehl-Button. */
  static final class InputMenu extends Menu {
    static final int INPUT_A = 10;
    static final int INPUT_B = 11;
    static final int BUTTON = 0;

    int buttonClicks;
    final List<CloseReason> closes = new ArrayList<>();
    final List<CloseReason> abandons = new ArrayList<>();

    InputMenu() {
      super(
          Component.text("input"),
          3,
          SlotPolicy.builder()
              .input(INPUT_A, item -> item.getType() == Material.DIAMOND)
              .input(INPUT_B, item -> item.getType() == Material.DIAMOND)
              .build());
      setButton(BUTTON, Button.of(new ItemStack(Material.PAPER), ctx -> buttonClicks++));
    }

    @Override
    protected void onClose(org.bukkit.entity.Player player, CloseReason reason) {
      closes.add(reason);
    }

    @Override
    protected void onAbandon(UUID playerId, CloseReason reason) {
      abandons.add(reason);
    }
  }

  /** Rueckgabesenke, die alles aufzeichnet. */
  static final class RecordingSink implements ReturnSink {
    final List<ItemStack> items = new ArrayList<>();
    final List<UUID> owners = new ArrayList<>();

    @Override
    public void returnItems(UUID playerId, List<ItemStack> returned) {
      owners.add(playerId);
      returned.forEach(i -> items.add(i.clone()));
    }

    int count(Material material) {
      return items.stream()
          .filter(i -> i.getType() == material)
          .mapToInt(ItemStack::getAmount)
          .sum();
    }
  }

  protected ServerMock server;
  protected PluginMock plugin;
  protected PlayerMock player;
  protected DirectScheduler scheduler;
  protected RecordingSink sink;
  protected MenuManager manager;
  protected MenuListener listener;
  protected final AtomicLong clockNanos = new AtomicLong(1_000_000_000_000L);

  @BeforeEach
  void setUpBase() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    scheduler = new DirectScheduler();
    sink = new RecordingSink();
    manager =
        new MenuManager(
            Logger.getLogger("test"),
            scheduler,
            sink,
            new MenuConfig(150),
            e -> true,
            clockNanos::get);
    listener = new MenuListener(manager, Logger.getLogger("test"));
    server.getPluginManager().registerEvents(listener, plugin);
    player = server.addPlayer();
  }

  @AfterEach
  void tearDownBase() {
    MockBukkit.unmock();
  }

  /** Ersetzt Manager und Listener (z. B. mit einem Scheduler, der nichts ausfuehrt). */
  protected void rebuild(
      PluginScheduler customScheduler, java.util.function.Predicate<Entity> owned) {
    org.bukkit.event.HandlerList.unregisterAll(plugin);
    manager =
        new MenuManager(
            Logger.getLogger("test"),
            customScheduler,
            sink,
            new MenuConfig(150),
            owned,
            clockNanos::get);
    listener = new MenuListener(manager, Logger.getLogger("test"));
    server.getPluginManager().registerEvents(listener, plugin);
  }

  protected InputMenu openInputMenu() {
    InputMenu menu = new InputMenu();
    manager.open(player, menu);
    return menu;
  }

  protected Inventory top() {
    return player.getOpenInventory().getTopInventory();
  }

  protected void advance(long millis) {
    clockNanos.addAndGet(millis * 1_000_000L);
  }

  /** Feuert ein Klick-Event; Raw-Slots ab {@code top().getSize()} liegen im Spielerinventar. */
  protected InventoryClickEvent fire(ClickType type, InventoryAction action, int rawSlot) {
    SlotType slotType =
        rawSlot < 0
            ? SlotType.OUTSIDE
            : rawSlot < top().getSize() ? SlotType.CONTAINER : SlotType.QUICKBAR;
    InventoryClickEvent event =
        new InventoryClickEvent(player.getOpenInventory(), slotType, rawSlot, type, action);
    server.getPluginManager().callEvent(event);
    return event;
  }

  protected InventoryClickEvent fireHotbar(int rawSlot, int key, InventoryAction action) {
    InventoryClickEvent event =
        new InventoryClickEvent(
            player.getOpenInventory(),
            SlotType.CONTAINER,
            rawSlot,
            ClickType.NUMBER_KEY,
            action,
            key);
    server.getPluginManager().callEvent(event);
    return event;
  }

  /** Wendet ein erlaubtes PLACE_ALL/PICKUP_ALL im oberen Inventar an. */
  protected void applyAllowed(InventoryClickEvent event) {
    if (event.isCancelled()) {
      return;
    }
    Inventory top = top();
    int slot = event.getRawSlot();
    if (event.getAction() == InventoryAction.PLACE_ALL) {
      top.setItem(slot, player.getItemOnCursor());
      player.setItemOnCursor(null);
    } else if (event.getAction() == InventoryAction.PICKUP_ALL) {
      player.setItemOnCursor(top.getItem(slot));
      top.setItem(slot, null);
    }
  }

  /** Legt {@code stack} auf den Cursor, klickt PLACE_ALL auf {@code slot} und wendet es an. */
  protected InventoryClickEvent place(ItemStack stack, int slot) {
    player.setItemOnCursor(stack);
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PLACE_ALL, slot);
    applyAllowed(event);
    return event;
  }

  /** Summe eines Materials ueber Spielerinventar, Cursor, oberes Inventar und Senke. */
  protected int total(Material material) {
    int sum = count(player.getInventory(), material);
    if (player.getOpenInventory().getTopInventory() != null) {
      sum += count(top(), material);
    }
    if (player.getItemOnCursor().getType() == material) {
      sum += player.getItemOnCursor().getAmount();
    }
    return sum + sink.count(material);
  }

  protected static int count(Inventory inventory, Material material) {
    int sum = 0;
    for (ItemStack item : java.util.Objects.requireNonNull(inventory.getContents())) {
      if (item != null && item.getType() == material) {
        sum += item.getAmount();
      }
    }
    return sum;
  }

  protected int inPlayerInventory(Material material) {
    PlayerInventory inventory = player.getInventory();
    return count(inventory, material);
  }
}
