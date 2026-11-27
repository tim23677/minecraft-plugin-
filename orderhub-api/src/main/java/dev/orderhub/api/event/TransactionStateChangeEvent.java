package dev.orderhub.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Ein Journal-Eintrag hat einen neuen Zustand erreicht. Asynchron und rein informativ. In dieser
 * Version wird das Ereignis fuer Auftragserstellung und Lieferungen ausgeloest (Zustaende CREATED,
 * COMMITTED), nicht fuer jeden internen Zustandswechsel der Auszahlungswarteschlange.
 *
 * @since 1.0.0
 */
public final class TransactionStateChangeEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  private final UUID journalId;
  private final String type;
  private final String state;
  private final long amountMinor;

  /**
   * Legt das Ereignis an.
   *
   * @param journalId Id des Journal-Eintrags
   * @param type Art der Transaktion
   * @param state neuer Zustand
   * @param amountMinor Betrag in Minor Units
   * @since 1.0.0
   */
  public TransactionStateChangeEvent(UUID journalId, String type, String state, long amountMinor) {
    super(true);
    this.journalId = journalId;
    this.type = type;
    this.state = state;
    this.amountMinor = amountMinor;
  }

  /**
   * Id des Journal-Eintrags.
   *
   * @return die Id
   * @since 1.0.0
   */
  public UUID journalId() {
    return journalId;
  }

  /**
   * Art der Transaktion.
   *
   * @return die Art als Text
   * @since 1.0.0
   */
  public String type() {
    return type;
  }

  /**
   * Neuer Zustand.
   *
   * @return der Zustand als Text
   * @since 1.0.0
   */
  public String state() {
    return state;
  }

  /**
   * Betrag der Transaktion.
   *
   * @return Minor Units
   * @since 1.0.0
   */
  public long amountMinor() {
    return amountMinor;
  }

  /**
   * Liste der Handler (Bukkit-Vorgabe).
   *
   * @return die Handler-Liste
   * @since 1.0.0
   */
  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  /**
   * Liste der Handler (Bukkit-Vorgabe fuer statische Registrierung).
   *
   * @return die Handler-Liste
   * @since 1.0.0
   */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
