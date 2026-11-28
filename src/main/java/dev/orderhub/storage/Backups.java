package dev.orderhub.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Sicherung einer SQLite-Datenbank im laufenden Betrieb ({@code VACUUM INTO}: konsistenter
 * Schnappschuss ohne den Betrieb anzuhalten). Blockiert und darf nie auf einem Server-Thread
 * laufen. MariaDB wird nicht hiermit gesichert (mysqldump oder Snapshot des Servers).
 */
public final class Backups {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

  private Backups() {}

  /** Schreibt {@code <prefix>-<zeit>.db} in {@code dir} und behaelt die neuesten {@code keep}. */
  public static Path sqliteBackup(DataSource dataSource, Path dir, String prefix, int keep)
      throws SQLException, IOException {
    Files.createDirectories(dir);
    Path target = dir.resolve(prefix + "-" + STAMP.format(Instant.now()) + ".db");
    Path temp = dir.resolve(target.getFileName() + ".tmp");
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
      ps.setString(1, temp.toAbsolutePath().toString());
      ps.execute();
      Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
    prune(dir, prefix, keep);
    return target;
  }

  /**
   * Sichert die Datenbank vor einer Schema-Migration, wenn welche aussteht und die Datenbank schon
   * Daten hat. Eine frische Datenbank wird nicht gesichert.
   *
   * @return der Pfad der Sicherung oder leer, wenn keine noetig war
   */
  public static Optional<Path> beforeMigration(DataSource dataSource, Dialect dialect, Path dir)
      throws SQLException, IOException {
    if (dialect != Dialect.SQLITE) {
      return Optional.empty();
    }
    int total = Migrations.load(dialect).size();
    int applied;
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_version")) {
      applied = rs.next() ? rs.getInt(1) : 0;
    } catch (SQLException e) {
      return Optional.empty(); // noch keine Tabelle: frische Datenbank
    }
    if (applied == 0 || applied >= total) {
      return Optional.empty();
    }
    return Optional.of(sqliteBackup(dataSource, dir, "pre-migration-v" + applied, 5));
  }

  private static void prune(Path dir, String prefix, int keep) throws IOException {
    String family = prefix.startsWith("pre-migration") ? "pre-migration" : prefix;
    try (Stream<Path> files = Files.list(dir)) {
      List<Path> old =
          files
              .filter(p -> String.valueOf(p.getFileName()).startsWith(family))
              .filter(p -> String.valueOf(p.getFileName()).endsWith(".db"))
              .sorted(
                  java.util.Comparator.comparing((Path p) -> String.valueOf(p.getFileName()))
                      .reversed())
              .skip(Math.max(1, keep))
              .toList();
      for (Path p : old) {
        Files.deleteIfExists(p);
      }
    }
  }
}
