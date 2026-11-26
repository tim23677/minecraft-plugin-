package dev.orderhub.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Duenne Abstraktion ueber den Bukkit- und den Folia-Scheduler. Aller Code, der Tasks startet, geht
 * durch dieses Interface, nie direkt durch Bukkit.getScheduler().
 */
public interface PluginScheduler {

  /** Task auf dem Thread der Region, die {@code location} besitzt. */
  void runAt(Location location, Runnable task);

  /** Task auf dem Thread, der {@code entity} besitzt. */
  void runOn(Entity entity, Runnable task);

  /** Task im globalen Kontext (kein Welt- oder Entity-Bezug). */
  void runGlobal(Runnable task);

  /** Task ausserhalb der Server-Threads (I/O, Datenbank). */
  void runAsync(Runnable task);

  /** Bricht alle von diesem Scheduler gestarteten Tasks ab. */
  void cancelAll();

  /** Waehlt anhand der Laufzeitumgebung die passende Implementierung. */
  static PluginScheduler create(Plugin plugin) {
    return FoliaDetector.isFolia()
        ? new FoliaPluginScheduler(plugin)
        : new BukkitPluginScheduler(plugin);
  }
}
