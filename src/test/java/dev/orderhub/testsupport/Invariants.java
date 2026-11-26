package dev.orderhub.testsupport;

import dev.orderhub.domain.Order;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Systemweite Invarianten. {@link #check} liefert die Liste der Verletzungen (leer = ok). */
public final class Invariants {

  private Invariants() {}

  public static List<String> check(Harness h, long initialMoney) {
    InMemoryStorage.Snapshot s = h.storage.snapshot();
    List<String> bad = new ArrayList<>();

    // 1. Escrow-Invariante je Auftrag
    long openEscrow = 0;
    for (Order o : s.orders()) {
      if (!o.escrowConsistent()) {
        bad.add("escrow inconsistent for order " + o.id() + ": " + o);
      }
      if (!o.status().isTerminal()) {
        openEscrow += o.escrowRemaining();
      }
    }

    // 2. Ledger-Erhaltung: reserviert == offen + ausgezahlt + Steuer + erstattet
    long reserved = sum(s.ledger(), LedgerRepository.Kind.ESCROW_RESERVE);
    long paid = sum(s.ledger(), LedgerRepository.Kind.PAYOUT);
    long tax = sum(s.ledger(), LedgerRepository.Kind.SALES_TAX);
    long refunded = sum(s.ledger(), LedgerRepository.Kind.REFUND);
    long fees = sum(s.ledger(), LedgerRepository.Kind.CREATE_FEE);
    if (reserved != openEscrow + paid + tax + refunded) {
      bad.add(
          "ledger: reserved="
              + reserved
              + " open="
              + openEscrow
              + " paid="
              + paid
              + " tax="
              + tax
              + " refunded="
              + refunded);
    }

    // 3. Geld nachvollziehbar
    long queueOpen = 0;
    long uncertain = 0;
    for (PayoutRepository.Item i : s.payouts()) {
      switch (i.state()) {
        case PENDING, FAILED -> queueOpen += i.amount();
        case IN_PROGRESS, REVIEW -> uncertain += i.amount();
        case COMPLETED -> {}
      }
    }
    for (JournalEntry j : s.journal()) {
      if (j.type() == TxType.ESCROW_CREATE
          && (j.state() == TxState.PREPARED || j.state() == TxState.MANUAL_REVIEW)) {
        uncertain += j.amount();
      }
    }
    long observed = h.economy.total() + openEscrow + fees + tax + queueOpen;
    if (observed > initialMoney || observed < initialMoney - uncertain) {
      bad.add(
          "money: initial="
              + initialMoney
              + " observed="
              + observed
              + " uncertain="
              + uncertain
              + " (balances="
              + h.economy.total()
              + " escrow="
              + openEscrow
              + " fees="
              + fees
              + " tax="
              + tax
              + " queue="
              + queueOpen
              + ")");
    }

    // 4. keine Doppelzahlung je Referenz
    if (!h.economy.duplicateReferences().isEmpty()) {
      bad.add("duplicate economy references: " + h.economy.duplicateReferences());
    }

    // 5. abgeschlossene Queue-Zeilen haben abgeschlossene Journale
    Map<UUID, JournalEntry> journal = new HashMap<>();
    s.journal().forEach(j -> journal.put(j.id(), j));
    for (PayoutRepository.Item i : s.payouts()) {
      JournalEntry j = journal.get(i.txId());
      if (j == null) {
        bad.add("queue row without journal " + i.id());
      } else if (i.state() == PayoutRepository.State.COMPLETED
          && j.state() != TxState.PAYOUT_COMPLETED
          && j.state() != TxState.REFUND_COMPLETED) {
        bad.add("completed row with journal in " + j.state());
      }
    }

    // 6. Items: nie Claim und Return zur selben Lieferung, Claim nur nach Commit
    Map<UUID, ParcelRepository.Parcel> claimBySource = new HashMap<>();
    s.claims().forEach(p -> claimBySource.put(p.sourceTx(), p));
    Map<UUID, ParcelRepository.Parcel> returnBySource = new HashMap<>();
    s.returns().forEach(p -> returnBySource.put(p.sourceTx(), p));
    Map<Long, Integer> filledByJournals = new HashMap<>();
    for (JournalEntry j : s.journal()) {
      if (j.type() != TxType.DELIVERY) {
        continue;
      }
      boolean claim = claimBySource.containsKey(j.id());
      boolean ret = returnBySource.containsKey(j.id());
      if (claim && ret) {
        bad.add("delivery " + j.id() + " has claim AND return");
      }
      if (claim) {
        filledByJournals.merge(j.orderId(), j.quantity(), Integer::sum);
      }
      boolean afterCommit =
          j.state() == TxState.COMMITTED
              || j.state() == TxState.PAYOUT_PENDING
              || j.state() == TxState.PAYOUT_IN_PROGRESS
              || j.state() == TxState.PAYOUT_COMPLETED;
      if (afterCommit && !claim) {
        bad.add("committed delivery " + j.id() + " without claim parcel");
      }
      if (j.state() == TxState.CREATED || j.state() == TxState.PREPARED) {
        if (claim || ret) {
          bad.add("early delivery " + j.id() + " has parcels");
        }
      }
    }
    for (Order o : s.orders()) {
      int filled = filledByJournals.getOrDefault(o.id(), 0);
      if (filled != o.quantityFilled()) {
        bad.add("order " + o.id() + " filled=" + o.quantityFilled() + " claims=" + filled);
      }
    }
    return bad;
  }

  private static long sum(List<LedgerRepository.Entry> ledger, LedgerRepository.Kind kind) {
    return ledger.stream()
        .filter(e -> e.kind() == kind)
        .mapToLong(LedgerRepository.Entry::amount)
        .sum();
  }

  /** Summe der Itemmengen aller Pakete einer Art zur Lieferung. */
  public static int parcelQuantity(List<ParcelRepository.Parcel> parcels, UUID sourceTx) {
    return parcels.stream()
        .filter(p -> p.sourceTx().equals(sourceTx))
        .mapToInt(ParcelRepository.Parcel::quantity)
        .sum();
  }
}
