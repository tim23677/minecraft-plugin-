package dev.orderhub.storage;

/** Migration fehlgeschlagen oder bereits angewendete Migration wurde veraendert. */
public final class MigrationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MigrationException(String message) {
    super(message);
  }

  public MigrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
