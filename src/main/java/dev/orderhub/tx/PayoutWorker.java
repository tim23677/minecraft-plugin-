package dev.orderhub.tx;

import dev.orderhub.economy.EconomyBridge;
import dev.orderhub.economy.EconomyResult;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Arbeitet die Auszahlungs- und Erstattungs-Queue ab. Jede Zeile wird per Compare-and-Set
 * beansprucht (IN_PROGRESS), die Economy wird ausserhalb jeder DB-Transaktion aufgerufen. Bei
 * unklarem Ausgang wird nie wiederholt, sondern nach MANUAL_REVIEW eskaliert. Mehrere Worker und
 * Server duerfen parallel laufen, weil nur der Gewinner des Claim-CAS zahlt.
 *
 * <p>Der Backoff gilt pro Worker-Instanz (keine Zeit je Zeile im Storage-Vertrag).
 */
public final class PayoutWorker {

  /** Ergebnis eines Laufs. */
  public record RunReport(
      int completed, int released, int escalated, boolean economyProblem, boolean skippedBackoff) {}

  private static final int COMPLETION_RETRIES = 3;

  private final Storage storage;
  private final EconomyBridge economy;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration maxBackoff;
  private long notBeforeMillis;
  private int consecutiveFailures;

  public PayoutWorker(
      Storage storage,
      EconomyBridge economy,
      Clock clock,
      int maxAttempts,
      Duration baseBackoff,
      Duration maxBackoff) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts");
    }
    this.storage = storage;
    this.economy = economy;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.baseBackoff = baseBackoff;
    this.maxBackoff = maxBackoff;
  }

  /** Worker mit 5 Versuchen, 5 s Basis- und 5 min Maximal-Backoff. */
  public static PayoutWorker withDefaults(Storage storage, EconomyBridge economy) {
    return new PayoutWorker(
        storage, economy, Clock.systemUTC(), 5, Duration.ofSeconds(5), Duration.ofMinutes(5));
  }

  /** Verarbeitet bis zu {@code maxItems} Zeilen nacheinander. */
  public synchronized RunReport runOnce(int maxItems) {
    if (clock.millis() < notBeforeMillis) {
      return new RunReport(0, 0, 0, false, true);
    }
    int completed = 0;
    int released = 0;
    int escalated = 0;
    boolean problem = false;
    for (int i = 0; i < maxItems; i++) {
      Optional<PayoutRepository.Item> claimed = TxRunner.run(storage, this::claimOne);
      if (claimed.isEmpty()) {
        break;
      }
      PayoutRepository.Item item = claimed.get();
      EconomyResult result;
      try {
        result = economy.deposit(item.player(), item.amount(), "payout:" + item.id());
      } catch (RuntimeException e) {
        // jede Ausnahme nach dem Aufruf: Ausgang unklar
        escalate(item, "deposit outcome unknown: " + e.getClass().getSimpleName());
        escalated++;
        continue;
      }
      if (result == EconomyResult.OK) {
        consecutiveFailures = 0;
        if (complete(item)) {
          completed++;
        }
      } else {
        if (releaseOrEscalate(item, result)) {
          escalated++;
        } else {
          released++;
        }
        problem = true;
        consecutiveFailures++;
        long delay =
            Math.min(
                maxBackoff.toMillis(),
                baseBackoff.toMillis() << Math.min(20, consecutiveFailures - 1));
        notBeforeMillis = clock.millis() + delay;
        break;
      }
    }
    return new RunReport(completed, released, escalated, problem, false);
  }

  /** Beansprucht eine Zeile und setzt bei Auszahlungen das Journal auf PAYOUT_IN_PROGRESS. */
  private Optional<PayoutRepository.Item> claimOne(dev.orderhub.storage.UnitOfWork uow) {
    var batch = uow.payouts().claimBatch(1, uow.now());
    if (batch.isEmpty()) {
      return Optional.empty();
    }
    PayoutRepository.Item item = batch.get(0);
    Optional<JournalEntry> journal = uow.journal().find(item.txId());
    TxState expected =
        item.kind() == PayoutRepository.Kind.PAYOUT
            ? TxState.PAYOUT_PENDING
            : TxState.REFUND_PENDING;
    if (journal.isEmpty() || journal.get().state() != expected) {
      uow.payouts().markReview(item.id(), "journal inconsistent", uow.now());
      journal.ifPresent(j -> {
        if (j.state().canTransitionTo(TxState.MANUAL_REVIEW)) {
          JournalSteps.to(uow, j, TxState.MANUAL_REVIEW, "queue/journal mismatch");
        }
      });
      return Optional.empty();
    }
    if (item.kind() == PayoutRepository.Kind.PAYOUT) {
      JournalSteps.to(uow, journal.get(), TxState.PAYOUT_IN_PROGRESS, "deposit started");
    }
    return Optional.of(item);
  }

  /** Bucht den Erfolg. Scheitert die DB, bleibt IN_PROGRESS und die Recovery eskaliert. */
  private boolean complete(PayoutRepository.Item item) {
    for (int attempt = 0; attempt < COMPLETION_RETRIES; attempt++) {
      try {
        return TxRunner.run(
            storage,
            uow -> {
              if (!uow.payouts().markCompleted(item.id(), uow.now())) {
                return false;
              }
              JournalEntry j = uow.journal().find(item.txId()).orElseThrow();
              TxState target =
                  item.kind() == PayoutRepository.Kind.PAYOUT
                      ? TxState.PAYOUT_COMPLETED
                      : TxState.REFUND_COMPLETED;
              JournalSteps.to(uow, j, target, "paid");
              return true;
            });
      } catch (RuntimeException e) {
        // erneut versuchen, danach der Recovery ueberlassen
      }
    }
    return false;
  }

  /**
   * @return {@code true}, wenn nach MANUAL_REVIEW eskaliert wurde
   */
  private boolean releaseOrEscalate(PayoutRepository.Item item, EconomyResult result) {
    boolean exhausted = item.attempts() + 1 >= maxAttempts;
    try {
      return TxRunner.run(
          storage,
          uow -> {
            JournalEntry j = uow.journal().find(item.txId()).orElseThrow();
            if (exhausted) {
              uow.payouts().markReview(item.id(), "max attempts: " + result, uow.now());
              if (j.state().canTransitionTo(TxState.MANUAL_REVIEW)) {
                JournalSteps.to(uow, j, TxState.MANUAL_REVIEW, "max attempts: " + result);
              }
              return true;
            }
            if (uow.payouts().release(item.id(), uow.now())
                && item.kind() == PayoutRepository.Kind.PAYOUT) {
              JournalSteps.to(uow, j, TxState.PAYOUT_PENDING, "retry after " + result);
            }
            return false;
          });
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void escalate(PayoutRepository.Item item, String reason) {
    try {
      TxRunner.run(
          storage,
          uow -> {
            uow.payouts().markReview(item.id(), reason, uow.now());
            JournalEntry j = uow.journal().find(item.txId()).orElseThrow();
            if (j.state().canTransitionTo(TxState.MANUAL_REVIEW)) {
              JournalSteps.to(uow, j, TxState.MANUAL_REVIEW, reason);
            }
            return null;
          });
    } catch (RuntimeException e) {
      // Zeile bleibt IN_PROGRESS, die Recovery eskaliert beim Start
    }
  }
}
