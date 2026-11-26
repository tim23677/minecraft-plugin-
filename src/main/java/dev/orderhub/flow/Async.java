package dev.orderhub.flow;

import dev.orderhub.scheduler.PluginScheduler;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Hilfen fuer den Wechsel Entity-Thread, Async-Thread und zurueck. Datenbankarbeit laeuft nie auf
 * dem Entity-Thread, Spielerzugriffe nie auf dem Async-Thread.
 */
final class Async {

  private Async() {}

  /**
   * Fuehrt {@code work} asynchron aus und uebergibt das Ergebnis im Entity-Kontext des Spielers.
   * Ist der Spieler nicht mehr erreichbar, wird {@code orphan} mit dem Ergebnis aufgerufen
   * (ebenfalls nicht auf dem Entity-Thread).
   */
  static <T> void supply(
      PluginScheduler scheduler,
      Logger logger,
      Player player,
      Supplier<T> work,
      Consumer<T> onPlayer,
      Consumer<T> orphan) {
    supply(scheduler, logger, player, work, onPlayer, orphan, null);
  }

  /**
   * Wie oben. {@code onError} laeuft im Entity-Kontext, wenn die Arbeit mit einer Ausnahme endet
   * (zum Beispiel volle DB-Queue), damit der Spieler nicht ohne Rueckmeldung stehen bleibt.
   */
  static <T> void supply(
      PluginScheduler scheduler,
      Logger logger,
      Player player,
      Supplier<T> work,
      Consumer<T> onPlayer,
      Consumer<T> orphan,
      Runnable onError) {
    scheduler.runAsync(() -> {
      T result;
      try {
        result = work.get();
      } catch (RuntimeException e) {
        logger.log(Level.SEVERE, "async work failed", e);
        if (onError != null) {
          scheduler.tryRunOn(player, onError);
        }
        return;
      }
      if (!scheduler.tryRunOn(player, () -> onPlayer.accept(result))) {
        orphan.accept(result);
      }
    });
  }
}
