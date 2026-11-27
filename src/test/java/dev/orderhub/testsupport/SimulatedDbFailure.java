package dev.orderhub.testsupport;

/** Simulierter Datenbankfehler (Verbindungsabbruch, Timeout). */
public final class SimulatedDbFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SimulatedDbFailure(String message) {
    super(message);
  }
}
