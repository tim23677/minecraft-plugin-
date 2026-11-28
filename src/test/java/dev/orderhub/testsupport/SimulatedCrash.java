package dev.orderhub.testsupport;

/**
 * Simulierter Prozessabbruch. Bewusst ein {@link Error}, damit kein {@code catch (Exception)} der
 * Engine ihn verschluckt: Nach einem echten Absturz laeuft kein weiterer Code.
 */
public final class SimulatedCrash extends Error {
  private static final long serialVersionUID = 1L;

  public SimulatedCrash(String message) {
    super(message);
  }
}
