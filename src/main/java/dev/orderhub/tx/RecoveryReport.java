package dev.orderhub.tx;

import java.util.List;
import java.util.UUID;

/** Ergebnis eines Recovery-Laufs. */
public record RecoveryReport(
    int failedBeforeItems,
    int itemsReturned,
    int committedResumed,
    int payoutsVerified,
    int payoutsEscalated,
    int refundsEscalated,
    int createsEscalated,
    int claimsReleased,
    int returnsReleased,
    int skippedFresh,
    int errors,
    int existingManualReview,
    List<Escalation> escalations) {

  /** Ein nach MANUAL_REVIEW eskalierter Fall. */
  public record Escalation(UUID journalId, TxType type, UUID player, long amount, String reason) {}

  public RecoveryReport {
    escalations = List.copyOf(escalations);
  }

  public boolean hasEscalations() {
    return !escalations.isEmpty();
  }

  /** Kurzfassung fuer Logs und Audit. */
  public String summary() {
    return "recovery: failedBeforeItems="
        + failedBeforeItems
        + " itemsReturned="
        + itemsReturned
        + " committedResumed="
        + committedResumed
        + " payoutsVerified="
        + payoutsVerified
        + " payoutsEscalated="
        + payoutsEscalated
        + " refundsEscalated="
        + refundsEscalated
        + " createsEscalated="
        + createsEscalated
        + " claimsReleased="
        + claimsReleased
        + " returnsReleased="
        + returnsReleased
        + " skippedFresh="
        + skippedFresh
        + " errors="
        + errors
        + " existingManualReview="
        + existingManualReview;
  }
}
