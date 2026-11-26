package dev.orderhub.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Scheduler fuer Paper und Spigot (ein Hauptthread). */
final class BukkitPluginScheduler implements PluginScheduler {

  private final Plugin plugin;

  BukkitPluginScheduler(Plugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void runAt(Location location, Runnable task) {
    plugin.getServer().getScheduler().runTask(plugin, task);
  }

  @Override
  public void runOn(Entity entity, Runnable task) {
    plugin.getServer().getScheduler().runTask(plugin, task);
  }

  @Override
  public boolean tryRunOn(Entity entity, Runnable task) {
    if (!plugin.isEnabled()) {
      return false;
    }
    try {
      plugin.getServer().getScheduler().runTask(plugin, task);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public void runGlobal(Runnable task) {
    plugin.getServer().getScheduler().runTask(plugin, task);
  }

  @Override
  public void runAsync(Runnable task) {
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
  }

  @Override
  public void cancelAll() {
    plugin.getServer().getScheduler().cancelTasks(plugin);
  }
}
