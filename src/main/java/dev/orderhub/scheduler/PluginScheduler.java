package dev.orderhub.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Duenne Abstraktion ueber den Bukkit- und den Folia-Scheduler. Aller Code, der Tasks startet, geht
 * durch dieses Interface, nie direkt durch Bukkit.getScheduler().
 *
 * <p>Pruefung der Zweige: Der Bukkit-Zweig (Paper) ist mit MockBukkit getestet. Der Folia-Zweig ist
 * durch keinen Unit-Test abgedeckt, MockBukkit kennt keine Regionen. Er wird nur durch den echten
 * Serverlauf mit mcstage ({@code tools/mcstage/run.sh}) auf Laden und Aktivieren geprueft, nicht
 * auf das Verhalten einzelner Tasks.
 *
 * <p>Verzoegerungen und Perioden von {@link #runLater} und {@link #runTimer} sind Ticks (mindestens
 * 1), die der Async-Methoden Millisekunden.
 */
public interface PluginScheduler {

  /** Task auf dem Thread der Region, die {@code location} besitzt. */
  void runAt(Location location, Runnable task);

  /**
   * Task auf dem Thread, der {@code entity} besitzt.
   *
   * <p>Rueckgabewert statt Retired-Callback: Der Aufrufer muss auf eine bereits entfernte Entity
   * reagieren koennen (zum Beispiel Items persistieren statt einem getrennten Spieler zu geben),
   * und ein Callback auf einem fremden Thread macht das umstaendlicher als ein {@code boolean}. Auf
   * Folia wird trotzdem ein Retired-Callback uebergeben, damit der Task nicht still verschwindet.
   *
   * @return {@code true}, wenn der Task angenommen wurde. {@code false}, wenn die Entity nicht mehr
   *     existiert (Folia: Retired, Paper: ungueltig oder Spieler getrennt) oder das Plugin keine
   *     Tasks mehr einplanen darf. Der Task laeuft dann nie.
   */
  boolean runOn(Entity entity, Runnable task);

  /**
   * Wie {@link #runOn}, faengt zusaetzlich jede Ausnahme des Schedulers ab. Bleibt fuer bestehende
   * Aufrufer erhalten.
   */
  default boolean tryRunOn(Entity entity, Runnable task) {
    try {
      return runOn(entity, task);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Task im globalen Kontext (kein Welt- oder Entity-Bezug). */
  void runGlobal(Runnable task);

  /** Wie {@link #runGlobal}, nach {@code delayTicks} Ticks (mindestens 1). */
  void runLater(Runnable task, long delayTicks);

  /**
   * Wiederkehrender Task im globalen Kontext. Erster Lauf nach {@code initialDelayTicks}, danach
   * alle {@code periodTicks} Ticks (jeweils mindestens 1). Laeuft bis {@link #cancelAll()}.
   */
  void runTimer(Runnable task, long initialDelayTicks, long periodTicks);

  /** Task ausserhalb der Server-Threads (I/O, Datenbank). */
  void runAsync(Runnable task);

  /**
   * Fuehrt {@code work} ausserhalb der Server-Threads aus und liefert das Ergebnis. Eine Ausnahme
   * von {@code work} oder ein abgelehnter Task (Plugin deaktiviert) beendet das Future
   * exceptionally. Callbacks am Future laufen auf dem Async-Thread, fuer Spieler- oder Weltzugriffe
   * muss der Aufrufer selbst per {@link #runOn} wechseln.
   */
  default <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      runAsync(() -> {
        try {
          future.complete(work.get());
        } catch (RuntimeException e) {
          future.completeExceptionally(e);
        }
      });
    } catch (RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  /**
   * Wiederkehrender Task ausserhalb der Server-Threads (Worker, Bereinigung). Er laeuft bis {@link
   * #cancelAll()} und darf lange I/O enthalten, aber nie zwei Laeufe gleichzeitig starten.
   */
  void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis);

  /** Ob dieser Scheduler noch neue Tasks annimmt (false nach dem Disable). */
  default boolean isActive() {
    return true;
  }

  /**
   * Bricht alle von diesem Scheduler gestarteten Tasks ab, auch Region- und Entity-Tasks. Beendete
   * Tasks werden aus der internen Liste entfernt.
   */
  void cancelAll();

  /** Waehlt anhand der Laufzeitumgebung die passende Implementierung. */
  static PluginScheduler create(Plugin plugin) {
    return FoliaDetector.isFolia()
        ? new FoliaPluginScheduler(plugin)
        : new BukkitPluginScheduler(plugin);
  }
}
