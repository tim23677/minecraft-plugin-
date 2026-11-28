package dev.orderhub.service;

import dev.orderhub.storage.Backups;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.Storage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Zeitgesteuerte Sicherung der SQLite-Datenbank. Blockiert, gehoert auf einen Worker-Thread. Bei
 * MariaDB passiert nichts: Dort sichert der Betreiber mit mysqldump oder einem Snapshot (siehe
 * Wiki, Abschnitt Sicherung).
 *
 * @since 2.0.0
 */
public final class AutoBackup {

  /** Dateiprefix der automatischen Sicherungen. */
  public static final String PREFIX = "auto";

  private AutoBackup() {}

  /**
   * @param keep wie viele automatische Sicherungen bleiben (die neuesten)
   * @return Pfad der neuen Sicherung oder leer, wenn der Dialekt keine Sicherung unterstuetzt
   */
  public static Optional<Path> run(Storage storage, Path dataDir, int keep)
      throws SQLException, IOException {
    if (!(storage instanceof JdbcStorage jdbc) || storage.dialect() != Dialect.SQLITE) {
      return Optional.empty();
    }
    return Optional.of(
        Backups.sqliteBackup(jdbc.dataSource(), dataDir.resolve("backups"), PREFIX, keep));
  }
}
