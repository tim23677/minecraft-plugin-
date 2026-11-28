package dev.orderhub.storage;

import java.sql.SQLException;

/** Unchecked Huelle fuer {@link SQLException} in den synchronen Repositories. */
public final class StorageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StorageException(String message, SQLException cause) {
    super(message, cause);
  }

  public StorageException(SQLException cause) {
    super(cause.getMessage(), cause);
  }
}
