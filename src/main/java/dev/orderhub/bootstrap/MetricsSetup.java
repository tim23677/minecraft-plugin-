package dev.orderhub.bootstrap;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Startet bStats, wenn der Betreiber es nicht abgeschaltet hat und eine Plugin-Id eingetragen ist.
 * Es werden nur anonyme Nutzungsdaten gesendet (Version, Datenbanktyp, Sprache, Anzahl offener
 * Auftraege in Stufen), keine Spielerdaten. Betreiber koennen bStats ausserdem serverweit in {@code
 * plugins/bStats/config.yml} abschalten.
 *
 * @since 2.0.0
 */
public final class MetricsSetup {

  /**
   * Plugin-Id von bStats. 0 bedeutet: noch nicht registriert, es wird nichts gesendet. Die Id wird
   * beim Registrieren des Plugins auf bstats.org vergeben und hier eingetragen.
   */
  public static final int PLUGIN_ID = 0;

  private MetricsSetup() {}

  /** Beschreibung des Ergebnisses, damit der Start im Log erklaert, warum bStats ruht. */
  public enum Result {
    STARTED,
    DISABLED_BY_CONFIG,
    NO_PLUGIN_ID
  }

  /**
   * @param enabled Wert von {@code metrics.enabled}
   * @param pluginId Plugin-Id bei bStats
   * @param start startet die Messung, wird nur bei {@link Result#STARTED} aufgerufen
   */
  public static Result start(boolean enabled, int pluginId, Runnable start) {
    if (!enabled) {
      return Result.DISABLED_BY_CONFIG;
    }
    if (pluginId <= 0) {
      return Result.NO_PLUGIN_ID;
    }
    start.run();
    return Result.STARTED;
  }

  /** Ordnet die Zahl offener Auftraege einer groben Stufe zu (keine genauen Zahlen senden). */
  public static String bucket(int openOrders) {
    if (openOrders <= 0) {
      return "0";
    }
    if (openOrders < 10) {
      return "1-9";
    }
    if (openOrders < 100) {
      return "10-99";
    }
    if (openOrders < 1000) {
      return "100-999";
    }
    return "1000+";
  }

  /** Fuer Aufrufer, die nur eine Stufe aus einer Quelle brauchen. */
  public static Supplier<String> bucketOf(IntSupplier openOrders) {
    return () -> bucket(openOrders.getAsInt());
  }
}
