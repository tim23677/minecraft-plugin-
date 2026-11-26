package dev.orderhub.economy;

/**
 * Der Provider hat waehrend withdraw/deposit eine Ausnahme geworfen. Ob die Operation ausgefuehrt
 * wurde, ist unbekannt. Aufrufer duerfen nie blind wiederholen, sondern eskalieren nach
 * MANUAL_REVIEW.
 */
public final class EconomyOutcomeUnknownException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public EconomyOutcomeUnknownException(String message, Throwable cause) {
    super(message, cause);
  }
}
