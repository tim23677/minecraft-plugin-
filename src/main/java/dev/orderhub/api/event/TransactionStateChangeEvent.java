package dev.orderhub.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Ein Journal-Eintrag hat einen neuen Zustand erreicht. Asynchron und rein informativ. In dieser
 * Version wird das Ereignis fuer Auftragserstellung und Lieferungen ausgeloest (Zustaende CREATED,
 * COMMITTED), nicht fuer jeden internen Zustandswechsel der Auszahlungswarteschlange.
 */
public final class TransactionStateChangeEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final UUID journalId;
  private final String type;
  private final String state;
  private final long amountMinor;

  public TransactionStateChangeEvent(UUID journalId, String type, String state, long amountMinor) {
    super(true);
    this.journalId = journalId;
    this.type = type;
    this.state = state;
    this.amountMinor = amountMinor;
  }

  public UUID journalId() {
    return journalId;
  }

  public String type() {
    return type;
  }

  public String state() {
    return state;
  }

  public long amountMinor() {
    return amountMinor;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
