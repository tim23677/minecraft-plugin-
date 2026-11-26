package dev.orderhub.tx;

import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stellt nach einem Absturz einen konsistenten Zustand her (siehe ARCHITECTURE.md). Grundsatz: Was
 * sicher nicht ausgefuehrt wurde, wird zurueckgerollt oder fortgesetzt. Was unklar ist (Zahlung
 * moeglicherweise ausgefuehrt), geht nach MANUAL_REVIEW und wird nie automatisch wiederholt.
 *
 * <p>{@code staleAfter} schuetzt im Mehrserverbetrieb laufende Operationen anderer Server: nur
 * Eintraege, die mindestens so lange unveraendert sind, werden angefasst. Im Einzelserverbetrieb
 * ist {@link Duration#ZERO} richtig.
 */
public final class RecoveryService {

  private static final int PAGE = 500;

  /** Nur waehrend eines (synchronisierten) Laufs gesetzt. */
  private volatile long activeStaleBefore;

  private final Storage storage;
  private final ClaimEngine claims;
  private final ReturnEngine returns;
  private final AuditSink audit;
  private final Clock clock;
  private final Duration staleAfter;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Engines sind injizierte, gemeinsam genutzte Dienste, keine Wertobjekte")
  public RecoveryService(
      Storage storage,
      ClaimEngine claims,
      ReturnEngine returns,
      AuditSink audit,
      Clock clock,
      Duration staleAfter) {
    this.storage = storage;
    this.claims = claims;
    this.returns = returns;
    this.audit = audit;
    this.clock = clock;
    this.staleAfter = staleAfter;
  }

  /** Laeuft einmal beim Start, bevor Worker und Menues aktiv werden. */
  public RecoveryReport runAtStartup() {
    return sweep();
  }

  /** Wie {@link #runAtStartup()}, auch periodisch nutzbar (haengende Eintraege nachziehen). */
  public synchronized RecoveryReport sweep() {
    long now = clock.millis();
    long staleBefore = now - staleAfter.toMillis();
    activeStaleBefore = staleBefore;
    Counters c = new Counters();

    // Reihenfolge: zuerst die Zustaende ohne Geldwirkung, dann Queue, dann Pakete.
    handleStates(EnumSet.of(TxState.CREATED, TxState.PREPARED), staleBefore, c, this::earlyState);
    handleStates(EnumSet.of(TxState.ITEMS_REMOVED), staleBefore, c, this::itemsRemoved);
    handleStates(EnumSet.of(TxState.COMMITTED), staleBefore, c, this::committed);
    handleStates(EnumSet.of(TxState.PAYOUT_PENDING), staleBefore, c, this::payoutPending);
    handleStates(EnumSet.of(TxState.PAYOUT_IN_PROGRESS), staleBefore, c, this::inProgress);
    escalateQueueInProgress(staleBefore, c);
    var hangingClaims = claims.releaseHanging(staleBefore);
    c.claimsReleased += hangingClaims.released();
    c.claimsEscalated += hangingClaims.escalations().size();
    c.escalations.addAll(hangingClaims.escalations());
    var hangingReturns = returns.releaseHanging(staleBefore);
    c.returnsReleased += hangingReturns.released();
    c.returnsEscalated += hangingReturns.escalations().size();
    c.escalations.addAll(hangingReturns.escalations());
    c.existingManualReview =
        TxRunner.run(
                storage,
                uow -> uow.journal().findByStates(EnumSet.of(TxState.MANUAL_REVIEW), 10_000))
            .size();

    RecoveryReport report = c.toReport();
    audit.recoveryReport(report, now);
    if (report.hasEscalations() || report.errors() > 0) {
      audit.adminWarning(
          "OrderHub recovery: "
              + report.escalations().size()
              + " Faelle in MANUAL_REVIEW, "
              + report.errors()
              + " Fehler. Details im Audit-Log.");
    }
    return report;
  }

  // ------------------------------------------------------------ Hilfen

  private interface Handler {
    /** Verarbeitet einen Eintrag in einer Transaktion. @return ob sich der Zustand aenderte */
    boolean handle(
        UnitOfWork uow, JournalEntry entry, Counters c, List<RecoveryReport.Escalation> out);
  }

  private record Outcome(boolean ran, boolean changed) {}

  private void handleStates(EnumSet<TxState> states, long staleBefore, Counters c, Handler h) {
    int progress;
    do {
      progress = 0;
      List<JournalEntry> batch =
          TxRunner.run(storage, uow -> uow.journal().findByStates(states, PAGE));
      for (JournalEntry listed : batch) {
        if (listed.updatedAt() > staleBefore) {
          c.freshIds.add(listed.id());
          continue;
        }
        try {
          Counters local = new Counters();
          List<RecoveryReport.Escalation> escalations = new ArrayList<>();
          Outcome outcome =
              TxRunner.run(
                  storage,
                  uow -> {
                    Optional<JournalEntry> fresh = uow.journal().find(listed.id());
                    if (fresh.isEmpty() || !states.contains(fresh.get().state())) {
                      return new Outcome(false, false);
                    }
                    if (fresh.get().updatedAt() > staleBefore) {
                      // zwischen Auflistung und Verarbeitung fortgeschrieben (anderer Server)
                      c.freshIds.add(listed.id());
                      return new Outcome(false, false);
                    }
                    local.reset();
                    escalations.clear();
                    return new Outcome(true, h.handle(uow, fresh.get(), local, escalations));
                  });
          if (outcome.ran()) {
            c.add(local);
            c.escalations.addAll(escalations);
          }
          if (outcome.changed()) {
            progress++;
          }
        } catch (RuntimeException e) {
          c.errors++;
        }
      }
    } while (progress > 0);
  }

  private static RecoveryReport.Escalation escalate(UnitOfWork uow, JournalEntry e, String reason) {
    JournalSteps.to(uow, e, TxState.MANUAL_REVIEW, reason);
    return new RecoveryReport.Escalation(e.id(), e.type(), e.playerId(), e.amount(), reason);
  }

  // ------------------------------------------------------------ Zustaende

  private boolean earlyState(
      UnitOfWork uow, JournalEntry e, Counters c, List<RecoveryReport.Escalation> out) {
    switch (e.type()) {
      case DELIVERY, CLAIM, ITEM_RETURN -> {
        JournalSteps.to(uow, e, TxState.FAILED, "recovered: nothing had happened");
        c.failedBeforeItems++;
      }
      case ESCROW_CREATE -> {
        if (e.state() == TxState.CREATED) {
          JournalSteps.to(uow, e, TxState.FAILED, "recovered: no withdraw");
          c.failedBeforeItems++;
        } else {
          out.add(escalate(uow, e, "escrow withdraw in flight at crash"));
          c.createsEscalated++;
        }
      }
      default -> {
        out.add(escalate(uow, e, "unexpected early state for " + e.type()));
        c.createsEscalated++;
      }
    }
    return true;
  }

  private boolean itemsRemoved(
      UnitOfWork uow, JournalEntry e, Counters c, List<RecoveryReport.Escalation> out) {
    if (e.type() == TxType.DELIVERY) {
      DeliveryEngine.failWithReturn(uow, e, "recovered: items returned to supplier");
      c.itemsReturned++;
    } else {
      out.add(escalate(uow, e, "unexpected ITEMS_REMOVED for " + e.type()));
      c.createsEscalated++;
    }
    return true;
  }

  private boolean committed(
      UnitOfWork uow, JournalEntry e, Counters c, List<RecoveryReport.Escalation> out) {
    switch (e.type()) {
      case DELIVERY -> {
        ensureQueued(uow, e, PayoutRepository.Kind.PAYOUT);
        JournalSteps.to(uow, e, TxState.PAYOUT_PENDING, "recovered: resumed");
        c.committedResumed++;
        return true;
      }
      case CANCEL_REFUND, EXPIRE_REFUND -> {
        ensureQueued(uow, e, PayoutRepository.Kind.REFUND);
        JournalSteps.to(uow, e, TxState.REFUND_PENDING, "recovered: resumed");
        c.committedResumed++;
        return true;
      }
      default -> {
        // ESCROW_CREATE: COMMITTED ist der Endzustand "Auftrag angelegt".
        // CLAIM/ITEM_RETURN laufen ueber CLAIM_PENDING, ADMIN_ADJUST ist manuell.
        return false;
      }
    }
  }

  private boolean payoutPending(
      UnitOfWork uow, JournalEntry e, Counters c, List<RecoveryReport.Escalation> out) {
    if (e.type() == TxType.DELIVERY) {
      ensureQueued(uow, e, PayoutRepository.Kind.PAYOUT);
    }
    c.payoutsVerified++;
    return false;
  }

  private boolean inProgress(
      UnitOfWork uow, JournalEntry e, Counters c, List<RecoveryReport.Escalation> out) {
    PayoutRepository.Item row =
        uow.payouts().findByTx(e.id(), PayoutRepository.Kind.PAYOUT).orElse(null);
    if (row != null && row.state() == PayoutRepository.State.IN_PROGRESS) {
      if (row.updatedAt() > activeStaleBefore) {
        // ein anderer Server arbeitet gerade an der Zeile
        c.freshIds.add(e.id());
        return false;
      }
      if (!uow.payouts()
          .markReviewIfUnchanged(row.id(), row.updatedAt(), "crash during payout", uow.now())) {
        c.freshIds.add(e.id());
        return false;
      }
    }
    out.add(escalate(uow, e, "payout in progress at crash, outcome unknown"));
    c.payoutsEscalated++;
    return true;
  }

  /** Refund-Zeilen haben keinen IN_PROGRESS-Journalzustand, sie stehen nur in der Queue. */
  private void escalateQueueInProgress(long staleBefore, Counters c) {
    List<PayoutRepository.Item> rows =
        TxRunner.run(
            storage, uow -> uow.payouts().findByState(PayoutRepository.State.IN_PROGRESS, PAGE));
    for (PayoutRepository.Item item : rows) {
      if (item.updatedAt() > staleBefore) {
        c.freshIds.add(item.id());
        continue;
      }
      try {
        RecoveryReport.Escalation esc =
            TxRunner.run(
                storage,
                uow -> {
                  // frisch lesen: nur eine unveraenderte, veraltete IN_PROGRESS-Zeile anfassen
                  PayoutRepository.Item row =
                      uow.payouts().findByTx(item.txId(), item.kind()).orElse(null);
                  if (row == null || row.state() != PayoutRepository.State.IN_PROGRESS) {
                    return null;
                  }
                  if (row.updatedAt() > staleBefore) {
                    c.freshIds.add(row.id());
                    return null;
                  }
                  if (!uow.payouts()
                      .markReviewIfUnchanged(
                          row.id(), row.updatedAt(), "crash during queue work", uow.now())) {
                    return null;
                  }
                  JournalEntry j = uow.journal().find(item.txId()).orElse(null);
                  if (j != null && j.state().canTransitionTo(TxState.MANUAL_REVIEW)) {
                    return escalate(uow, j, "refund/payout in progress at crash, outcome unknown");
                  }
                  return new RecoveryReport.Escalation(
                      item.txId(), null, item.player(), item.amount(), "queue row in progress");
                });
        if (esc != null) {
          c.escalations.add(esc);
          if (item.kind() == PayoutRepository.Kind.REFUND) {
            c.refundsEscalated++;
          } else {
            c.payoutsEscalated++;
          }
        }
      } catch (RuntimeException e) {
        c.errors++;
      }
    }
  }

  private static void ensureQueued(UnitOfWork uow, JournalEntry e, PayoutRepository.Kind kind) {
    if (e.amount() > 0 && uow.payouts().findByTx(e.id(), kind).isEmpty()) {
      uow.payouts().enqueue(e.id(), e.playerId(), kind, e.amount(), uow.now());
    }
  }

  /** Zaehler, nicht thread-sicher; ein Recovery-Lauf ist einthreadig. */
  private static final class Counters {
    int failedBeforeItems;
    int itemsReturned;
    int committedResumed;
    int payoutsVerified;
    int payoutsEscalated;
    int refundsEscalated;
    int createsEscalated;
    int claimsReleased;
    int returnsReleased;
    int claimsEscalated;
    int returnsEscalated;
    final Set<UUID> freshIds = new HashSet<>();
    int errors;
    int existingManualReview;
    final List<RecoveryReport.Escalation> escalations = new ArrayList<>();

    void reset() {
      failedBeforeItems = 0;
      itemsReturned = 0;
      committedResumed = 0;
      payoutsVerified = 0;
      payoutsEscalated = 0;
      refundsEscalated = 0;
      createsEscalated = 0;
    }

    void add(Counters o) {
      freshIds.addAll(o.freshIds);
      failedBeforeItems += o.failedBeforeItems;
      itemsReturned += o.itemsReturned;
      committedResumed += o.committedResumed;
      payoutsVerified += o.payoutsVerified;
      payoutsEscalated += o.payoutsEscalated;
      refundsEscalated += o.refundsEscalated;
      createsEscalated += o.createsEscalated;
    }

    RecoveryReport toReport() {
      return new RecoveryReport(
          failedBeforeItems,
          itemsReturned,
          committedResumed,
          payoutsVerified,
          payoutsEscalated,
          refundsEscalated,
          createsEscalated,
          claimsReleased,
          returnsReleased,
          claimsEscalated,
          returnsEscalated,
          freshIds.size(),
          errors,
          existingManualReview,
          escalations);
    }
  }
}
