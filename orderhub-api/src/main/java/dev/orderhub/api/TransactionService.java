package dev.orderhub.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Lesender Zugriff auf das Transaction Journal. Es gibt keine schreibenden Methoden.
 *
 * @since 1.0.0
 */
public interface TransactionService {

  /**
   * Ein Journal-Eintrag.
   *
   * @param id Id des Eintrags
   * @param orderId zugehoeriger Auftrag oder {@code null}
   * @param player beteiligter Spieler
   * @param type Art der Transaktion als Text
   * @param state Zustand als Text
   * @param quantity Stueckzahl
   * @param amountMinor Betrag in Minor Units
   * @param createdAt Erstellzeit in Millisekunden seit 1970
   * @param updatedAt letzte Aenderung in Millisekunden seit 1970
   * @since 1.0.0
   */
  record TransactionView(
      UUID id,
      Long orderId,
      UUID player,
      String type,
      String state,
      int quantity,
      long amountMinor,
      long createdAt,
      long updatedAt) {}

  /**
   * Liest einen Eintrag.
   *
   * @param id die Id
   * @return der Eintrag oder leer
   * @since 1.0.0
   */
  CompletableFuture<Optional<TransactionView>> getTransaction(UUID id);

  /**
   * Liest die Eintraege eines Spielers, neueste zuerst, hoechstens 100.
   *
   * @param player der Spieler
   * @param limit Anzahl, auf 1 bis 100 begrenzt
   * @return die Eintraege, nie {@code null}
   * @since 1.0.0
   */
  CompletableFuture<List<TransactionView>> getTransactions(UUID player, int limit);
}
