package dev.orderhub.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/**
 * Auftraege lesen, erstellen und stornieren.
 *
 * <p>Eingaben werden geprueft wie bei den Befehlen im Spiel. Ungueltige Eingaben fuehren zu einem
 * Statuswert im Ergebnis ({@code ERROR}, {@code INVALID_PRICE}, {@code INVALID_QUANTITY}, {@code
 * ITEM_REJECTED}), nie zu einer Ausnahme und nie zu einer Buchung.
 *
 * @since 1.0.0
 */
public interface OrderService {

  /**
   * Wunsch nach einem neuen Auftrag. Der Item-Zustand wird beim Aufruf kopiert und serverseitig
   * geprueft (Sperrliste, Verzauberungen, Container). {@code requestId} ist der Idempotency Key:
   * Derselbe Wert fuehrt nie zu einem zweiten Auftrag.
   *
   * @param requestId Idempotency Key, nicht {@code null}
   * @param owner Auftraggeber, nicht {@code null}
   * @param item Item, das bestellt wird (Menge wird ignoriert), nicht {@code null}
   * @param quantity gewuenschte Menge, 1 bis zum konfigurierten Maximum
   * @param unitPriceMinor Stueckpreis in Minor Units, innerhalb der konfigurierten Grenzen
   * @param lifetime Laufzeit oder {@code null} fuer den Standard der Konfiguration
   * @since 1.0.0
   */
  record CreateRequest(
      UUID requestId,
      UUID owner,
      ItemStack item,
      int quantity,
      long unitPriceMinor,
      Duration lifetime) {
    /**
     * Kopiert das Item, damit der Aufrufer es danach nicht mehr veraendern kann.
     *
     * @since 1.0.0
     */
    public CreateRequest {
      item = item == null ? null : item.clone();
    }

    /**
     * Eine Kopie des Items.
     *
     * @return Kopie des Items oder {@code null}
     * @since 1.0.0
     */
    @Override
    public ItemStack item() {
      return item == null ? null : item.clone();
    }
  }

  /**
   * Ergebnis der Erstellung.
   *
   * @since 1.0.0
   */
  enum CreateStatus {
    /** Der Auftrag wurde angelegt und der Betrag reserviert. */
    CREATED,
    /** Dieselbe Anfrage wurde schon verarbeitet, der Auftrag existiert. */
    ALREADY_CREATED,
    /** Das Item ist fuer Auftraege nicht zugelassen. */
    ITEM_REJECTED,
    /** Der Preis liegt ausserhalb der Grenzen. */
    INVALID_PRICE,
    /** Die Menge liegt ausserhalb der Grenzen. */
    INVALID_QUANTITY,
    /** Der Auftraggeber hat sein Limit offener Auftraege erreicht. */
    LIMIT_REACHED,
    /** Der Auftraggeber hat nicht genug Geld. */
    INSUFFICIENT_FUNDS,
    /** Es ist kein Economy-Anbieter erreichbar. */
    ECONOMY_UNAVAILABLE,
    /** Der Auftraggeber darf keine Auftraege erstellen oder ist nicht pruefbar. */
    NO_PERMISSION,
    /** Die abgerechnete Gebuehr weicht vom erwarteten Betrag ab. */
    FEE_CHANGED,
    /** Fehlende oder ungueltige Eingabe, oder ein interner Fehler. */
    ERROR
  }

  /**
   * Ergebnis von {@link #createOrder(CreateRequest)}.
   *
   * @param status Ausgang
   * @param orderId Id bei Erfolg, sonst {@code null}
   * @param detail kurzer Text zum Ausgang fuer Logs
   * @since 1.0.0
   */
  record CreateResult(CreateStatus status, Long orderId, String detail) {
    /**
     * {@code true} bei {@link CreateStatus#CREATED} und {@link CreateStatus#ALREADY_CREATED}.
     *
     * @return ob der Auftrag existiert
     * @since 1.0.0
     */
    public boolean success() {
      return status == CreateStatus.CREATED || status == CreateStatus.ALREADY_CREATED;
    }
  }

  /**
   * Ein Auftrag. {@code status}: ACTIVE, PAUSED, COMPLETED, CANCELLED, EXPIRED oder ARCHIVED.
   *
   * @param id Auftrags-Id
   * @param owner Auftraggeber
   * @param itemKey Material-Key des Items, zum Beispiel {@code minecraft:diamond}
   * @param unitPriceMinor Stueckpreis in Minor Units
   * @param quantityTotal bestellte Menge
   * @param quantityFilled bisher gelieferte Menge
   * @param escrowRemainingMinor noch reservierter Betrag in Minor Units
   * @param status Status als Text
   * @param createdAt Erstellzeit in Millisekunden seit 1970
   * @param expiresAt Ablaufzeit in Millisekunden seit 1970
   * @since 1.0.0
   */
  record OrderView(
      long id,
      UUID owner,
      String itemKey,
      long unitPriceMinor,
      int quantityTotal,
      int quantityFilled,
      long escrowRemainingMinor,
      String status,
      long createdAt,
      long expiresAt) {}

  /**
   * Filter fuer {@link #getOpenOrders}. {@code null} bedeutet kein Filter. Seitengroesse max. 100.
   *
   * @param itemKey nur dieses Item oder {@code null}
   * @param owner nur Auftraege dieses Spielers oder {@code null}
   * @param offset Start der Seite, negative Werte zaehlen als 0
   * @param limit Seitengroesse, auf 1 bis 100 begrenzt
   * @since 1.0.0
   */
  record Filter(String itemKey, UUID owner, int offset, int limit) {}

  /**
   * Ausgang einer Stornierung.
   *
   * @since 1.0.0
   */
  enum CancelStatus {
    /** Der Auftrag wurde storniert, der Rest erstattet. */
    CANCELLED,
    /** Der Auftrag war schon beendet. */
    ALREADY_DONE,
    /** Es gibt keinen Auftrag mit dieser Id. */
    NOT_FOUND,
    /** Der Aufrufer ist nicht der Besitzer. */
    NOT_OWNER,
    /** Der Auftrag ist in einem Zustand, in dem er nicht storniert werden kann. */
    INVALID_STATE,
    /** Fehlende Eingabe oder interner Fehler. */
    ERROR
  }

  /**
   * Ergebnis von {@link #cancelOrder}.
   *
   * @param status Ausgang
   * @param refundedMinor erstatteter Betrag in Minor Units
   * @since 1.0.0
   */
  record CancelResult(CancelStatus status, long refundedMinor) {}

  /**
   * Erstellt einen Auftrag. Der Auftraggeber muss online sein und die Rechte haben.
   *
   * @param request die Anfrage, bei {@code null} oder fehlenden Feldern ergibt sich {@code ERROR}
   * @return Ergebnis, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<CreateResult> createOrder(CreateRequest request);

  /**
   * Optionen fuer {@link #createOrder(CreateRequest, CreateOptions)}.
   *
   * @param bypassChecks {@code true}: keine Rechte- und Limitpruefung des Auftraggebers,
   *     Standardlimit und volle Gebuehr. Gedacht fuer Plugins, die selbst fuer die Berechtigung
   *     buergen (z. B. Auftraege fuer Offline-Spieler). Der Aufruf wird mit dem aufrufenden Plugin
   *     im Audit-Log vermerkt.
   * @since 1.0.0
   */
  record CreateOptions(boolean bypassChecks) {
    /**
     * Ohne Umgehung der Pruefungen.
     *
     * @since 1.0.0
     */
    public static final CreateOptions DEFAULT = new CreateOptions(false);
  }

  /**
   * Wie {@link #createOrder(CreateRequest)}, mit ausdruecklichen Optionen.
   *
   * @param request die Anfrage
   * @param options die Optionen, {@code null} gilt als {@link CreateOptions#DEFAULT}
   * @return Ergebnis, nie {@code null}
   * @since 1.0.0
   */
  default CompletableFuture<CreateResult> createOrder(
      CreateRequest request, CreateOptions options) {
    return createOrder(request);
  }

  /**
   * Liest einen Auftrag.
   *
   * @param orderId Auftrags-Id
   * @return der Auftrag oder leer, wenn es ihn nicht gibt
   * @since 1.0.0
   */
  CompletableFuture<Optional<OrderView>> getOrder(long orderId);

  /**
   * Liest offene Auftraege, neueste zuerst.
   *
   * @param filter Filter, {@code null} gilt als erste Seite ohne Filter
   * @return die Seite, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<List<OrderView>> getOpenOrders(Filter filter);

  /**
   * Storniert einen Auftrag. Nur der Besitzer ({@code actor}) darf stornieren.
   *
   * @param orderId Auftrags-Id
   * @param actor der Akteur, nicht {@code null}, sonst {@code ERROR}
   * @return Ergebnis, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<CancelResult> cancelOrder(long orderId, UUID actor);
}
