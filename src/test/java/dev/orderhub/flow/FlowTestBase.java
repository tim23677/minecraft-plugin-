package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.bootstrap.Settings;
import dev.orderhub.config.Rates;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.gui.MenuConfig;
import dev.orderhub.gui.MenuListener;
import dev.orderhub.gui.MenuManager;
import dev.orderhub.item.Blocklist;
import dev.orderhub.item.ItemCodec;
import dev.orderhub.item.ItemMatcher;
import dev.orderhub.item.MatchPolicy;
import dev.orderhub.scheduler.PluginScheduler;
import dev.orderhub.service.OrderService;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.testsupport.Harness;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Verdrahtet die Bukkit-nahen Ablaeufe mit der In-Memory-Datenbank und einem Scheduler, der alles
 * sofort im aufrufenden Thread ausfuehrt. So laufen DeliveryFlow, ParcelFlow und Menues
 * vollstaendig durch, ohne Server-Ticks abzuwarten. Die Invariante lautet immer: Items vorher =
 * Items nachher.
 */
abstract class FlowTestBase {

  /** Fuehrt Tasks sofort aus. */
  static final class DirectScheduler implements PluginScheduler {
    @Override
    public void runAt(Location location, Runnable task) {
      task.run();
    }

    @Override
    public boolean runOn(Entity entity, Runnable task) {
      if (entity instanceof org.bukkit.entity.Player p && !p.isOnline()) {
        return false;
      }
      task.run();
      return true;
    }

    /** Fehler im Entity-Task sichtbar machen statt sie als "false" zu verschlucken. */
    @Override
    public boolean tryRunOn(Entity entity, Runnable task) {
      return runOn(entity, task);
    }

    @Override
    public void runGlobal(Runnable task) {
      task.run();
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
      task.run();
    }

    @Override
    public void runTimer(Runnable task, long initialDelayTicks, long periodTicks) {}

    /** Wenn gesetzt, werden Async-Tasks gesammelt und erst mit {@link #drain()} ausgefuehrt. */
    boolean deferAsync;

    private final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();

    @Override
    public void runAsync(Runnable task) {
      if (deferAsync) {
        queued.add(task);
      } else {
        task.run();
      }
    }

    void drain() {
      deferAsync = false;
      while (!queued.isEmpty()) {
        queued.poll().run();
      }
    }

    @Override
    public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {}

    @Override
    public void cancelAll() {}
  }

  protected static final UUID OWNER = Harness.player(1);

  @TempDir Path dir;
  protected ServerMock server;
  protected PluginMock plugin;
  protected PlayerMock supplier;
  protected DirectScheduler scheduler;
  protected Harness h;
  protected Services services;
  protected MenuManager menus;
  protected ItemCodec codec;
  protected final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);

  @BeforeEach
  void setUpFlow() throws Exception {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    h = new Harness();
    h.economy.setBalance(OWNER, 10_000_000);
    codec = new ItemCodec();
    scheduler = new DirectScheduler();
    Logger logger = Logger.getLogger("flow-test");
    Messages messages = new Messages();
    messages.load("en", dir, getClass().getClassLoader());
    YamlConfiguration yaml = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      yaml.load(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
    }
    Settings settings = Settings.from(yaml);
    ReturnQueue queue = new ReturnQueue(h.storage, scheduler, codec, logger);
    menus =
        new MenuManager(
            logger,
            scheduler,
            (owner, items) -> queue.persist(owner, items, false),
            new MenuConfig(0),
            e -> true,
            nanos::incrementAndGet);
    server.getPluginManager().registerEvents(new MenuListener(menus, logger), plugin);
    ItemMatcher matcher =
        new ItemMatcher(codec, MatchPolicy.strict(), Blocklist.parse(List.of(), true).blocklist());
    Rates rates = Rates.from(settings);
    services =
        new Services(
            logger,
            h.storage,
            scheduler,
            messages,
            () -> settings,
            () -> rates,
            h.orders,
            h.delivery,
            h.claims,
            h.returns,
            h.worker,
            codec,
            matcher,
            menus,
            null,
            null);
    supplier = server.addPlayer();
  }

  @AfterEach
  void tearDownFlow() {
    MockBukkit.unmock();
  }

  /** Auftrag des Besitzers fuer {@code quantity} Diamanten zu {@code price}. */
  protected long order(long price, int quantity) throws Exception {
    ItemSnapshot snapshot = codec.encode(new ItemStack(Material.DIAMOND));
    var request =
        new OrderService.CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            snapshot,
            price,
            quantity,
            1,
            null,
            false,
            "items",
            100,
            false);
    var result = h.orders.createOrder(request);
    assertThat(result.status()).isEqualTo(OrderService.CreateStatus.CREATED);
    return result.orderId();
  }

  protected Inventory top() {
    return supplier.getOpenInventory().getTopInventory();
  }

  protected void put(int slot, ItemStack stack) {
    supplier.setItemOnCursor(stack);
    InventoryClickEvent event =
        new InventoryClickEvent(
            supplier.getOpenInventory(),
            SlotType.CONTAINER,
            slot,
            ClickType.LEFT,
            InventoryAction.PLACE_ALL);
    server.getPluginManager().callEvent(event);
    if (!event.isCancelled()) {
      top().setItem(slot, supplier.getItemOnCursor());
      supplier.setItemOnCursor(null);
    }
  }

  protected void click(int slot) {
    click(supplier.getOpenInventory(), slot);
  }

  /** Klick auf eine zuvor gemerkte Ansicht, auch wenn sie inzwischen geschlossen wurde. */
  protected void click(org.bukkit.inventory.InventoryView view, int slot) {
    server
        .getPluginManager()
        .callEvent(
            new InventoryClickEvent(
                view, SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
  }

  /** Alle Diamanten: Spielerinventar, Cursor, offenes Menue, Rueckgaben, Claims, Reservierungen. */
  protected int diamondsEverywhere() {
    int sum = 0;
    for (ItemStack i : Objects.requireNonNull(supplier.getInventory().getContents())) {
      if (i != null && i.getType() == Material.DIAMOND) {
        sum += i.getAmount();
      }
    }
    if (supplier.getItemOnCursor().getType() == Material.DIAMOND) {
      sum += supplier.getItemOnCursor().getAmount();
    }
    Inventory open = supplier.getOpenInventory().getTopInventory();
    if (open != null && open.getSize() >= 54) {
      for (int slot : new int[] {10, 11, 12, 13}) { // nur die Eingabeslots, nicht das Info-Icon
        ItemStack i = open.getItem(slot);
        if (i != null && i.getType() == Material.DIAMOND) {
          sum += i.getAmount();
        }
      }
    }
    var snap = h.storage.snapshot();
    for (ParcelRepository.Parcel p : snap.claims()) {
      sum += p.quantity();
    }
    for (ParcelRepository.Parcel p : snap.returns()) {
      sum += p.quantity();
    }
    return sum;
  }

  protected int inInventory(PlayerMock player, Material material) {
    int sum = 0;
    for (ItemStack i : Objects.requireNonNull(player.getInventory().getContents())) {
      if (i != null && i.getType() == material) {
        sum += i.getAmount();
      }
    }
    return sum;
  }
}
