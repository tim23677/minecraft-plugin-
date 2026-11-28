package dev.orderhub.economy;

/** Ergebnis einer Wirtschaftsoperation. Erwartbare Faelle sind nie Exceptions. */
public enum EconomyResult {
  /** Vom Provider bestaetigt ausgefuehrt. */
  OK,
  /** Guthaben reicht nicht. Nichts wurde ausgefuehrt. */
  INSUFFICIENT,
  /** Provider hat die Operation sicher abgelehnt. Nichts wurde ausgefuehrt. */
  FAILED,
  /** Kein Provider verfuegbar. Nichts wurde ausgefuehrt. */
  UNAVAILABLE
}
