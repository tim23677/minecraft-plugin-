package dev.orderhub.economy;

import java.util.UUID;

/**
 * Port zur Spielerwirtschaft in Minor Units. Aufrufe duerfen nie innerhalb einer
 * Datenbanktransaktion stehen. Erwartbare Faelle liefern {@link EconomyResult}, nur ein unklarer
 * Ausgang wirft {@link EconomyOutcomeUnknownException}.
 */
public interface EconomyBridge {

  /** Prueft, ob der Spieler mindestens {@code minor} besitzt. */
  EconomyResult has(UUID player, long minor);

  /**
   * Bucht ab.
   *
   * @param reference Nachvollziehbarkeits-Kennung (z. B. Journal-Id), nur fuer Logs und Tests
   * @throws EconomyOutcomeUnknownException wenn der Ausgang unklar ist
   */
  EconomyResult withdraw(UUID player, long minor, String reference);

  /**
   * Schreibt gut.
   *
   * @throws EconomyOutcomeUnknownException wenn der Ausgang unklar ist
   */
  EconomyResult deposit(UUID player, long minor, String reference);
}
