package dev.orderhub.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler fuer Folia (regionalisierte Threads). Merkt sich die Handles aller Region-, Entity-,
 * globalen und Async-Tasks, bricht sie in {@link #cancelAll()} ab und raeumt beendete Handles beim
 * naechsten Einplanen auf.
 *
 * <p>Nicht durch Unit-Tests abgedeckt: MockBukkit kennt keine Regionen. Der Zweig wird nur durch
 * den Serverlauf mit mcstage auf Laden und Aktivieren geprueft.
 */
final class FoliaPluginScheduler implements PluginScheduler {

  private final Plugin plugin;
  private final Set<ScheduledTask> handles = ConcurrentHashMap.newKeySet();

  FoliaPluginScheduler(Plugin plugin) {
    this.plugin = plugin;
  }

  private static boolean finished(ScheduledTask task) {
    ScheduledTask.ExecutionState state = task.getExecutionState();
    return state == ScheduledTask.ExecutionState.FINISHED
        || state == ScheduledTask.ExecutionState.CANCELLED;
  }

  private ScheduledTask track(ScheduledTask task) {
    handles.removeIf(FoliaPluginScheduler::finished);
    handles.add(task);
    return task;
  }

  /** Anzahl der aktuell verfolgten, noch nicht beendeten Tasks (nach Aufraeumen). */
  int trackedTasks() {
    handles.removeIf(FoliaPluginScheduler::finished);
    return handles.size();
  }

  @Override
  public void runAt(Location location, Runnable task) {
    track(plugin.getServer().getRegionScheduler().run(plugin, location, t -> task.run()));
  }

  @Override
  public boolean runOn(Entity entity, Runnable task) {
    ScheduledTask scheduled =
        entity
            .getScheduler()
            .run(
                plugin,
                t -> task.run(),
                () ->
                    plugin
                        .getLogger()
                        .log(
                            Level.FINE,
                            "task dropped, entity {0} was retired",
                            entity.getUniqueId()));
    // null: Die Entity ist bereits entfernt, der Retired-Callback wird nicht mehr ausgeloest.
    if (scheduled == null) {
      return false;
    }
    track(scheduled);
    return true;
  }

  @Override
  public void runGlobal(Runnable task) {
    track(plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run()));
  }

  @Override
  public void runLater(Runnable task, long delayTicks) {
    track(
        plugin
            .getServer()
            .getGlobalRegionScheduler()
            .runDelayed(plugin, t -> task.run(), Math.max(1, delayTicks)));
  }

  @Override
  public void runTimer(Runnable task, long initialDelayTicks, long periodTicks) {
    track(
        plugin
            .getServer()
            .getGlobalRegionScheduler()
            .runAtFixedRate(
                plugin, t -> task.run(), Math.max(1, initialDelayTicks), Math.max(1, periodTicks)));
  }

  @Override
  public void runAsync(Runnable task) {
    track(plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run()));
  }

  @Override
  public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
    track(
        plugin
            .getServer()
            .getAsyncScheduler()
            .runAtFixedRate(
                plugin,
                t -> task.run(),
                Math.max(1, initialDelayMillis),
                Math.max(1, periodMillis),
                TimeUnit.MILLISECONDS));
  }

  @Override
  public boolean isActive() {
    return plugin.isEnabled();
  }

  @Override
  public void cancelAll() {
    for (ScheduledTask handle : handles) {
      handle.cancel();
    }
    handles.clear();
    plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
    plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
  }
}
