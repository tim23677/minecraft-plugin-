package dev.orderhub.domain;

/** Lebenszyklus eines Auftrags. */
public enum OrderStatus {
  ACTIVE,
  PAUSED,
  COMPLETED,
  CANCELLED,
  EXPIRED,
  ARCHIVED;

  /** Auftrag kann beliefert werden. */
  public boolean acceptsDeliveries() {
    return this == ACTIVE;
  }

  /** Auftrag ist beendet, der Escrow muss null sein. */
  public boolean isTerminal() {
    return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == ARCHIVED;
  }
}
