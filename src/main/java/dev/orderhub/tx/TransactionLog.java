package dev.orderhub.tx;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strukturierte Logzeile je Geld- und Item-Bewegung: {@code key=value} getrennt durch Leerzeichen,
 * mit Transaktions-Id, Spieler-UUID, Betrag und Zustand. Die Ausgabe laeuft ueber eine
 * austauschbare Senke ({@link #install}). Ohne Senke passiert nichts. Werte werden bereinigt, damit
 * eine Zeile nie mehrere Zeilen werden kann (kein Log-Injection ueber Itemnamen oder Gruende).
 *
 * @since 2.0.0
 */
public final class TransactionLog {

  /** Ziel der Zeilen. */
  @FunctionalInterface
  public interface Sink {
    void write(String line);
  }

  private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9:_.,/=+-]");
  private static final int MAX_VALUE = 120;
  private static volatile Sink sink = line -> {};

  private TransactionLog() {}

  /** Setzt die Senke, {@code null} schaltet das Protokoll ab. */
  public static void install(Sink newSink) {
    sink = newSink == null ? line -> {} : newSink;
  }

  /** Geldbewegung. */
  public static void money(String event, UUID tx, UUID player, long amount, String state) {
    emit(event, tx, player, "amount=" + amount, state, null);
  }

  /** Item-Bewegung, optional mit zugehoerigem Geldbetrag. */
  public static void items(
      String event,
      UUID tx,
      UUID player,
      String item,
      int quantity,
      long amount,
      String state,
      Long order) {
    emit(
        event,
        tx,
        player,
        "amount=" + amount + " item=" + clean(item) + " qty=" + quantity,
        state,
        order);
  }

  private static void emit(
      String event, UUID tx, UUID player, String fields, String state, Long order) {
    try {
      sink.write(
          "ts="
              + Instant.now()
              + " event="
              + clean(event)
              + " tx="
              + (tx == null ? "-" : tx)
              + " player="
              + (player == null ? "-" : player)
              + " "
              + fields
              + " state="
              + clean(state)
              + (order == null ? "" : " order=" + order));
    } catch (RuntimeException e) {
      // Ein defektes Protokoll darf nie eine Buchung stoeren.
    }
  }

  /** Ersetzt alles ausser einfachen Zeichen durch Unterstriche und kuerzt. */
  static String clean(String value) {
    if (value == null || value.isEmpty()) {
      return "-";
    }
    String cleaned = UNSAFE.matcher(value).replaceAll("_");
    return cleaned.length() > MAX_VALUE ? cleaned.substring(0, MAX_VALUE) : cleaned;
  }
}
