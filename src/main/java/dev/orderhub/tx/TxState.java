package dev.orderhub.tx;

import java.util.EnumSet;
import java.util.Set;

/** Zustaende eines Journal-Eintrags. Uebergaenge sind strikt ueber {@link #canTransitionTo}. */
public enum TxState {
  CREATED,
  PREPARED,
  ITEMS_REMOVED,
  COMMITTED,
  PAYOUT_PENDING,
  PAYOUT_IN_PROGRESS,
  PAYOUT_COMPLETED,
  REFUND_PENDING,
  REFUND_COMPLETED,
  DELIVERY_STORED,
  CLAIM_PENDING,
  CLAIM_COMPLETED,
  FAILED,
  MANUAL_REVIEW;

  /** Endzustaende, aus denen es keinen Uebergang mehr gibt. */
  public boolean isTerminal() {
    return this == PAYOUT_COMPLETED
        || this == REFUND_COMPLETED
        || this == CLAIM_COMPLETED
        || this == FAILED;
  }

  /** Zulaessige Folgezustaende. MANUAL_REVIEW ist aus jedem nicht-terminalen Zustand erreichbar. */
  public Set<TxState> successors() {
    Set<TxState> next =
        switch (this) {
          case CREATED -> EnumSet.of(PREPARED, FAILED);
          case PREPARED -> EnumSet.of(ITEMS_REMOVED, COMMITTED, FAILED);
          case ITEMS_REMOVED -> EnumSet.of(COMMITTED, FAILED);
          case COMMITTED ->
              EnumSet.of(PAYOUT_PENDING, REFUND_PENDING, DELIVERY_STORED, CLAIM_PENDING);
          case PAYOUT_PENDING -> EnumSet.of(PAYOUT_IN_PROGRESS, FAILED);
          case PAYOUT_IN_PROGRESS -> EnumSet.of(PAYOUT_COMPLETED, PAYOUT_PENDING);
          case REFUND_PENDING -> EnumSet.of(REFUND_COMPLETED, FAILED);
          case DELIVERY_STORED -> EnumSet.of(CLAIM_PENDING, PAYOUT_PENDING);
          case CLAIM_PENDING -> EnumSet.of(CLAIM_COMPLETED, DELIVERY_STORED);
          case MANUAL_REVIEW ->
              EnumSet.of(PAYOUT_COMPLETED, REFUND_COMPLETED, CLAIM_COMPLETED, FAILED);
          case PAYOUT_COMPLETED, REFUND_COMPLETED, CLAIM_COMPLETED, FAILED ->
              EnumSet.noneOf(TxState.class);
        };
    if (!isTerminal() && this != MANUAL_REVIEW) {
      next = EnumSet.copyOf(next);
      next.add(MANUAL_REVIEW);
    }
    return next;
  }

  public boolean canTransitionTo(TxState target) {
    return successors().contains(target);
  }
}
