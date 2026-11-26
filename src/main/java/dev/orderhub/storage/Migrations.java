package dev.orderhub.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Versionierter Migrationsrunner. Dateien liegen unter {@code db/migration/{common,sqlite,mariadb}}
 * und sind in {@code db/migration/migrations.list} aufgefuehrt (ein Jar kann Verzeichnisse nicht
 * zuverlaessig auflisten). Je Version wird zuerst die {@code common}-Datei, dann die Datei des
 * Dialekts angewendet. {@code migration_history} speichert je Datei den SHA-256; ein veraenderter
 * Hash einer bereits angewendeten Datei bricht ab.
 *
 * <p>Parallele Starts mehrerer Server: MariaDB serialisiert ueber {@code GET_LOCK}, SQLite ueber
 * {@code BEGIN IMMEDIATE}. SQLite-DDL ist transaktional (alles oder nichts). MariaDB-DDL committet
 * implizit; die Skripte nutzen deshalb {@code IF NOT EXISTS}, damit ein abgebrochener Lauf
 * wiederholbar bleibt.
 *
 * <p>Statements in den Dateien werden durch eine Zeile {@code -- @@} getrennt. Platzhalter: {@code
 * ${AUTO_ID}}, {@code ${BLOB}} und {@code ${TABLE_OPTS}} (MariaDB: InnoDB, utf8mb4_bin).
 */
public final class Migrations {

  static final String MANIFEST = "db/migration/migrations.list";
  static final String LOCK_NAME = "orderhub_migration";
  private static final int LOCK_TIMEOUT_SECONDS = 120;
  private static final Pattern FILE =
      Pattern.compile("(common|sqlite|mariadb)/V(\\d+)__(\\w+)\\.sql");
  private static final String SEPARATOR = "-- @@";

  private Migrations() {}

  /** Eine Migrationsdatei aus dem Manifest. */
  record Script(int version, String scope, String name, String path) {}

  /** Anwenden mit automatisch erkanntem Dialekt. */
  public static int apply(DataSource dataSource) throws SQLException {
    Dialect dialect;
    try (Connection c = dataSource.getConnection()) {
      dialect = detect(c);
    }
    return apply(dataSource, dialect);
  }

  /**
   * Wendet alle ausstehenden Migrationen an.
   *
   * @return Anzahl neu angewendeter Dateien
   */
  public static int apply(DataSource dataSource, Dialect dialect) throws SQLException {
    List<Script> scripts = load(dialect);
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(true);
      return switch (dialect) {
        case SQLITE -> applySqlite(c, scripts);
        case MARIADB -> applyMariaDb(c, scripts);
      };
    }
  }

  static Dialect detect(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
    if (product.contains("sqlite")) {
      return Dialect.SQLITE;
    }
    if (product.contains("mariadb") || product.contains("mysql")) {
      return Dialect.MARIADB;
    }
    throw new MigrationException("unsupported database: " + product);
  }

  private static int applySqlite(Connection c, List<Script> scripts) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("BEGIN IMMEDIATE");
    }
    try {
      int applied = run(c, scripts, Dialect.SQLITE);
      try (Statement s = c.createStatement()) {
        s.execute("COMMIT");
      }
      return applied;
    } catch (SQLException | RuntimeException e) {
      try (Statement s = c.createStatement()) {
        s.execute("ROLLBACK");
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  private static int applyMariaDb(Connection c, List<Script> scripts) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, ?)")) {
      ps.setString(1, LOCK_NAME);
      ps.setInt(2, LOCK_TIMEOUT_SECONDS);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next() || rs.getInt(1) != 1) {
          throw new MigrationException("could not acquire migration lock " + LOCK_NAME);
        }
      }
    }
    try {
      return run(c, scripts, Dialect.MARIADB);
    } finally {
      try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        ps.setString(1, LOCK_NAME);
        ps.execute();
      }
    }
  }

  private static int run(Connection c, List<Script> scripts, Dialect dialect) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS migration_history ("
              + "version INT NOT NULL, scope VARCHAR(16) NOT NULL, name VARCHAR(190) NOT NULL, "
              + "checksum CHAR(64) NOT NULL, applied_at BIGINT NOT NULL, "
              + "PRIMARY KEY (version, scope))");
    }
    Map<String, String> applied = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT version, scope, checksum FROM migration_history")) {
      while (rs.next()) {
        applied.put(rs.getInt(1) + "/" + rs.getString(2), rs.getString(3));
      }
    }
    int count = 0;
    for (Script script : scripts) {
      String content = read(script.path());
      String checksum = sha256(content);
      String key = script.version() + "/" + script.scope();
      String known = applied.get(key);
      if (known != null) {
        if (!known.equals(checksum)) {
          throw new MigrationException(
              "migration "
                  + script.path()
                  + " was modified after it was applied (expected "
                  + known
                  + ", found "
                  + checksum
                  + ")");
        }
        continue;
      }
      for (String statement : split(substitute(content, dialect))) {
        try (Statement s = c.createStatement()) {
          s.execute(statement);
        } catch (SQLException e) {
          throw new MigrationException(
              "migration " + script.path() + " failed: " + e.getMessage(), e);
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO migration_history(version, scope, name, checksum, applied_at) "
                  + "VALUES (?,?,?,?,?)")) {
        ps.setInt(1, script.version());
        ps.setString(2, script.scope());
        ps.setString(3, script.name());
        ps.setString(4, checksum);
        ps.setLong(5, System.currentTimeMillis());
        ps.executeUpdate();
      }
      count++;
    }
    return count;
  }

  /** Liest das Manifest und liefert die Dateien dieses Dialekts, sortiert nach Version. */
  static List<Script> load(Dialect dialect) {
    String scope = dialect == Dialect.SQLITE ? "sqlite" : "mariadb";
    List<Script> scripts = new ArrayList<>();
    for (String line : read(MANIFEST).split("\\R")) {
      String path = line.trim();
      if (path.isEmpty() || path.startsWith("#")) {
        continue;
      }
      Matcher m = FILE.matcher(path);
      if (!m.matches()) {
        throw new MigrationException("bad manifest entry: " + path);
      }
      if (m.group(1).equals("common") || m.group(1).equals(scope)) {
        scripts.add(new Script(Integer.parseInt(m.group(2)), m.group(1), m.group(3), path));
      }
    }
    // Stabil: gleiche Version behaelt Manifest-Reihenfolge (common vor Dialekt).
    scripts.sort(java.util.Comparator.comparingInt(Script::version));
    return scripts;
  }

  static String read(String path) {
    String resource = path.startsWith("db/") ? path : "db/migration/" + path;
    try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new MigrationException("missing migration resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new MigrationException("cannot read " + resource, e);
    }
  }

  static String substitute(String sql, Dialect dialect) {
    return switch (dialect) {
      case SQLITE ->
          sql.replace("${AUTO_ID}", "INTEGER PRIMARY KEY AUTOINCREMENT")
              .replace("${BLOB}", "BLOB")
              .replace("${TABLE_OPTS}", "");
      case MARIADB ->
          sql.replace("${AUTO_ID}", "BIGINT PRIMARY KEY AUTO_INCREMENT")
              .replace("${BLOB}", "LONGBLOB")
              .replace(
                  "${TABLE_OPTS}", "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    };
  }

  /** Teilt an Zeilen {@code -- @@} und verwirft leere Teile. */
  static List<String> split(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : sql.split("\\R", -1)) {
      if (line.trim().equals(SEPARATOR)) {
        flush(out, current);
      } else {
        current.append(line).append('\n');
      }
    }
    flush(out, current);
    return out;
  }

  private static void flush(List<String> out, StringBuilder current) {
    boolean hasCode =
        current
            .toString()
            .lines()
            .map(String::trim)
            .anyMatch(l -> !l.isEmpty() && !l.startsWith("--"));
    if (hasCode) {
      out.add(current.toString().strip());
    }
    current.setLength(0);
  }

  static String sha256(String content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
