package dev.orderhub.tx;

/** Unzulaessiger Zustandsuebergang im Journal. */
public final class InvalidTransitionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public InvalidTransitionException(TxState from, TxState to) {
    super("illegal transition " + from + " -> " + to);
  }
}
