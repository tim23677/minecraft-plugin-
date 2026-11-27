package dev.orderhub.testsupport;

import dev.orderhub.OrderHubPlugin;
import org.mockbukkit.mockbukkit.ServerMock;

/** Wartet im Test, bis OrderHub die Datenbank asynchron geoeffnet und sich verdrahtet hat. */
public final class PluginReady {

  private PluginReady() {}

  /**
   * Treibt Async- und Tick-Aufgaben, bis das Plugin bereit oder deaktiviert ist (hoechstens 20 s).
   *
   * @return {@code true}, wenn das Plugin bereit ist
   */
  public static boolean await(ServerMock server, OrderHubPlugin plugin) {
    long end = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < end) {
      server.getScheduler().waitAsyncTasksFinished();
      server.getScheduler().performOneTick();
      if (plugin.isReady()) {
        return true;
      }
      if (!plugin.isEnabled()) {
        return false;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return plugin.isReady();
  }
}
