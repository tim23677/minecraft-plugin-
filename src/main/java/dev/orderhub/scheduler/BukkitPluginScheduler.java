package dev.orderhub.scheduler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Scheduler fuer Paper (ein Hauptthread). Merkt sich alle Task-Handles, bricht sie in {@link
 * #cancelAll()} ab und raeumt beendete Handles beim naechsten Einplanen auf. Mit MockBukkit
 * getestet.
 */
final class BukkitPluginScheduler implements PluginScheduler {

  private final Plugin plugin;
  private final Set<BukkitTask> handles = ConcurrentHashMap.newKeySet();

  BukkitPluginScheduler(Plugin plugin) {
    this.plugin = plugin;
  }

  private BukkitScheduler bukkit() {
    return plugin.getServer().getScheduler();
  }

  /** Merkt das Handle und entfernt dabei alle Handles, die nicht mehr laufen oder anstehen. */
  private BukkitTask track(BukkitTask task) {
    BukkitScheduler scheduler = bukkit();
    handles.removeIf(
        h -> !scheduler.isQueued(h.getTaskId()) && !scheduler.isCurrentlyRunning(h.getTaskId()));
    handles.add(task);
    return task;
  }

  /** Anzahl der aktuell verfolgten, noch nicht beendeten Tasks (nach Aufraeumen). */
  int trackedTasks() {
    BukkitScheduler scheduler = bukkit();
    handles.removeIf(
        h -> !scheduler.isQueued(h.getTaskId()) && !scheduler.isCurrentlyRunning(h.getTaskId()));
    return handles.size();
  }

  @Override
  public void runAt(Location location, Runnable task) {
    track(bukkit().runTask(plugin, task));
  }

  @Override
  public boolean runOn(Entity entity, Runnable task) {
    if (!plugin.isEnabled() || !entity.isValid()) {
      return false;
    }
    if (entity instanceof Player p && !p.isOnline()) {
      return false;
    }
    track(bukkit().runTask(plugin, task));
    return true;
  }

  @Override
  public void runGlobal(Runnable task) {
    track(bukkit().runTask(plugin, task));
  }

  @Override
  public void runLater(Runnable task, long delayTicks) {
    track(bukkit().runTaskLater(plugin, task, Math.max(1, delayTicks)));
  }

  @Override
  public void runTimer(Runnable task, long initialDelayTicks, long periodTicks) {
    track(
        bukkit()
            .runTaskTimer(plugin, task, Math.max(1, initialDelayTicks), Math.max(1, periodTicks)));
  }

  @Override
  public void runAsync(Runnable task) {
    track(bukkit().runTaskAsynchronously(plugin, task));
  }

  @Override
  public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
    track(
        bukkit()
            .runTaskTimerAsynchronously(
                plugin,
                task,
                Math.max(1, initialDelayMillis / 50),
                Math.max(1, periodMillis / 50)));
  }

  @Override
  public boolean isActive() {
    return plugin.isEnabled();
  }

  @Override
  public void cancelAll() {
    for (BukkitTask handle : handles) {
      handle.cancel();
    }
    handles.clear();
    // Zusaetzlich alles, was dieses Plugin ueber andere Wege eingeplant haben koennte.
    bukkit().cancelTasks(plugin);
  }
}
