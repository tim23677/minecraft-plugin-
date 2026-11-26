package dev.orderhub.gui;

import dev.orderhub.scheduler.PluginScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Oeffnet Menues, verfolgt je Spieler das offene Menue und gibt Eingabeslot-Inhalte genau einmal
 * zurueck.
 *
 * <p>Threading: {@link #open} springt ueber {@link PluginScheduler#runOn} in den Entity-Kontext.
 * Alle {@code handle*}- und {@code abandon}-Methoden werden aus Events aufgerufen, die Paper und
 * Folia im Kontext des Spielers ausfuehren. {@link #closeAll()} ruft der Plugin-Disable auf.
 */
public final class MenuManager {

  private final Logger logger;
  private final PluginScheduler scheduler;
  private final ReturnSink returnSink;
  private final Predicate<Entity> ownedByCurrentContext;
  private final ClickDebounce debounce;
  private final Map<UUID, Menu> open = new ConcurrentHashMap<>();
  private volatile boolean disabled;

  public MenuManager(
      Logger logger, PluginScheduler scheduler, ReturnSink returnSink, MenuConfig config) {
    this(logger, scheduler, returnSink, config, Bukkit::isOwnedByCurrentRegion, System::nanoTime);
  }

  /** Mit austauschbarer Kontextpruefung und Uhr, fuer Tests. */
  public MenuManager(
      Logger logger,
      PluginScheduler scheduler,
      ReturnSink returnSink,
      MenuConfig config,
      Predicate<Entity> ownedByCurrentContext,
      LongSupplier nanoClock) {
    this.logger = Objects.requireNonNull(logger, "logger");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.returnSink = Objects.requireNonNull(returnSink, "returnSink");
    this.ownedByCurrentContext = Objects.requireNonNull(ownedByCurrentContext);
    this.debounce = new ClickDebounce(config.debounceMillis(), nanoClock);
  }

  /** Oeffnet {@code menu} fuer {@code player} im Entity-Kontext. */
  public void open(Player player, Menu menu) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(menu, "menu");
    if (disabled) {
      menu.markClosed();
      return;
    }
    if (!scheduler.tryRunOn(player, () -> openNow(player, menu))) {
      menu.markClosed(); // nie geoeffnet, nichts zurueckzugeben
    }
  }

  /** {@code true} nach {@link #closeAll()}: keine neuen Menues, alle Klicks gesperrt. */
  public boolean isDisabled() {
    return disabled;
  }

  private void openNow(Player player, Menu menu) {
    if (disabled || !player.isOnline() || menu.isClosed()) {
      menu.markClosed();
      return;
    }
    Menu previous = open.put(player.getUniqueId(), menu);
    if (previous != null && previous != menu) {
      finish(previous, player, CloseReason.REPLACED);
      // finish entfernt nur, wenn noch das alte Menue eingetragen ist; das neue bleibt erhalten.
    }
    try {
      menu.onOpen(player);
      player.openInventory(menu.getInventory());
    } catch (RuntimeException e) {
      finish(menu, player, CloseReason.PLAYER_CLOSE);
      throw e;
    }
  }

  /** Aktuell offenes OrderHub-Menue des Spielers. */
  public Optional<Menu> current(UUID playerId) {
    return Optional.ofNullable(open.get(playerId));
  }

  public int openCount() {
    return open.size();
  }

  /**
   * Prueft vor jedem Klick: Spieler online, Menue nicht beendet, genau dieses Menue ist das
   * verfolgte und das offene obere Inventar des Spielers.
   */
  boolean isCurrent(Player player, Menu menu) {
    if (disabled
        || !player.isOnline()
        || menu.isClosed()
        || open.get(player.getUniqueId()) != menu) {
      return false;
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    return MenuHolders.of(top) == menu;
  }

  boolean acquireClick(UUID playerId) {
    return debounce.tryAcquire(playerId);
  }

  /** Inventar wurde geschlossen. */
  public void handleClose(Player player, Inventory inventory, CloseReason reason) {
    Menu menu = MenuHolders.of(inventory);
    if (menu != null) {
      finish(menu, player, reason);
    }
  }

  /** Spieler verlaesst die Sitzung oder wechselt die Welt: offenes Menue beenden. */
  public void abandon(Player player, CloseReason reason) {
    Menu menu = open.get(player.getUniqueId());
    if (menu != null) {
      finish(menu, player, reason);
    }
    if (reason.abandoned() && reason != CloseReason.DEATH) {
      debounce.forget(player.getUniqueId());
    }
  }

  private void finish(Menu menu, Player player, CloseReason reason) {
    if (!menu.markClosed()) {
      return;
    }
    UUID playerId = player.getUniqueId();
    open.remove(playerId, menu);
    List<ItemStack> items = menu.drainInputs();
    try {
      if (reason.abandoned()) {
        menu.onAbandon(playerId, reason);
      } else {
        menu.onClose(player, reason);
      }
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE, "menu hook failed for " + playerId, e);
    }
    deliver(player, playerId, items, reason);
  }

  private void deliver(Player player, UUID playerId, List<ItemStack> items, CloseReason reason) {
    if (items.isEmpty()) {
      return;
    }
    List<ItemStack> leftovers = items;
    if (reason.returnsToPlayer() && player.isOnline() && ownedByCurrentContext.test(player)) {
      leftovers =
          new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
    }
    sendToSink(player, playerId, leftovers);
  }

  private void sendToSink(Player player, UUID playerId, List<ItemStack> leftovers) {
    if (leftovers.isEmpty()) {
      return;
    }
    try {
      returnSink.returnItems(playerId, List.copyOf(leftovers));
    } catch (RuntimeException e) {
      logger.log(
          Level.SEVERE,
          "return sink failed for " + playerId + ", " + leftovers.size() + " stacks at risk",
          e);
      lastResortDrop(player, leftovers);
    }
  }

  /** Letzter Ausweg, damit bei einem Fehler der Senke nichts verschwindet. */
  private void lastResortDrop(Player player, List<ItemStack> items) {
    if (player.isOnline() && ownedByCurrentContext.test(player)) {
      for (ItemStack item : items) {
        player.getWorld().dropItemNaturally(player.getLocation(), item);
      }
    } else {
      logger.severe("items could not be returned and the player is unreachable: " + items);
    }
  }

  /**
   * Plugin-Disable, in zwei Phasen.
   *
   * <p>Phase 1 laeuft synchron und ohne Entity-Scheduler: Der Manager wird gesperrt, jedes Menue
   * wird entschaerft (Eingabeslot-Inhalte heraus, Inventar samt Button-Items geleert) und die Items
   * gehen sofort an den persistenten {@link ReturnSink}. Damit kann nichts mehr verloren gehen oder
   * als Button-Item in ein Spielerinventar gelangen, auch wenn spaeter keine Aufgabe mehr laeuft
   * (Folia nach dem Disable).
   *
   * <p>Phase 2 schliesst nur noch die Ansicht der Spieler: direkt, wenn der Spieler im aktuellen
   * Kontext liegt, sonst per Entity-Scheduler. Wird die Aufgabe nicht angenommen, ist das
   * unkritisch, weil Phase 1 bereits alles gesichert hat.
   */
  public void closeAll() {
    disabled = true;
    List<UUID> viewers = new ArrayList<>();
    for (Map.Entry<UUID, Menu> entry : new ArrayList<>(open.entrySet())) {
      emergencyReturn(entry.getKey(), entry.getValue());
      viewers.add(entry.getKey());
    }
    for (UUID playerId : viewers) {
      closeView(playerId);
    }
  }

  private void closeView(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return;
    }
    if (ownedByCurrentContext.test(player)) {
      player.closeInventory();
    } else if (!scheduler.tryRunOn(player, player::closeInventory)) {
      logger.log(Level.FINE, "view of {0} stays open, menu is already disarmed", playerId);
    }
  }

  /** Entschaerft ein Menue ohne Spielerkontext: Inhalt in die Senke, Inventar leer. */
  private void emergencyReturn(UUID playerId, Menu menu) {
    if (!menu.markClosed()) {
      return;
    }
    open.remove(playerId, menu);
    List<ItemStack> items = menu.disarm();
    try {
      menu.onAbandon(playerId, CloseReason.PLUGIN_DISABLE);
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE, "menu hook failed for " + playerId, e);
    }
    if (!items.isEmpty()) {
      try {
        returnSink.returnItems(playerId, List.copyOf(items));
      } catch (RuntimeException e) {
        logger.log(Level.SEVERE, "return sink failed for " + playerId + ": " + items, e);
      }
    }
  }
}
