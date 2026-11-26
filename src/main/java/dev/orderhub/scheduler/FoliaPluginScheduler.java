package dev.orderhub.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Scheduler fuer Folia, nutzt die Region-, Entity- und Async-Scheduler der Paper-API. */
final class FoliaPluginScheduler implements PluginScheduler {

  private final Plugin plugin;

  FoliaPluginScheduler(Plugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void runAt(Location location, Runnable task) {
    plugin.getServer().getRegionScheduler().run(plugin, location, t -> task.run());
  }

  @Override
  public void runOn(Entity entity, Runnable task) {
    entity.getScheduler().run(plugin, t -> task.run(), null);
  }

  @Override
  public boolean tryRunOn(Entity entity, Runnable task) {
    try {
      return entity.getScheduler().run(plugin, t -> task.run(), null) != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void runGlobal(Runnable task) {
    plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run());
  }

  @Override
  public void runAsync(Runnable task) {
    plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
  }

  @Override
  public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
    plugin
        .getServer()
        .getAsyncScheduler()
        .runAtFixedRate(
            plugin,
            t -> task.run(),
            Math.max(1, initialDelayMillis),
            Math.max(1, periodMillis),
            java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  @Override
  public void cancelAll() {
    plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
    plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
  }
}
