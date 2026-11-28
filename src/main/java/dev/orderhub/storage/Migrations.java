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
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Versionierter Migrationsrunner. Dateien liegen unter {@code db/migration/{common,sqlite,mariadb}}
 * und sind in {@code db/migration/migrations.list} aufgefuehrt (ein Jar kann Verzeichnisse nicht
 * zuverlaessig auflisten). Je Version wird zuerst die {@code common}-Datei, dann die Datei des
 * Dialekts angewendet. {@code schema_version} speichert je Schritt Version, Name, SHA-256 und
 * Zeitpunkt; ein Schritt wird hoechstens einmal angewendet, ein veraenderter Hash einer bereits
 * angewendeten Datei bricht ab. Jeder Schritt laeuft in einer eigenen Transaktion.
 *
 * <p>Parallele Starts mehrerer Server: MariaDB serialisiert ueber {@code GET_LOCK}, SQLite ueber
 * {@code BEGIN IMMEDIATE} je Schritt. SQLite-DDL ist transaktional: Ein fehlgeschlagener Schritt
 * hinterlaesst nichts, frueher abgeschlossene Schritte bleiben. MariaDB-DDL committet implizit und
 * laesst sich nicht zurueckrollen; die Skripte nutzen deshalb {@code IF NOT EXISTS}, damit ein
 * abgebrochener Schritt beim naechsten Start wiederholbar ist, und die Zeile in {@code
 * schema_version} entsteht erst nach dem letzten Statement des Schritts.
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

  /** Quelle der Schritte. Die Standardquelle liest das Manifest im Klassenpfad. */
  interface Source {
    /** Schritte fuer den Dialekt (common und Dialekt-Datei), in beliebiger Reihenfolge. */
    List<Script> scripts(Dialect dialect);

    /** Inhalt einer Datei aus {@link #scripts}. */
    String read(String path);
  }

  private static final Source CLASSPATH =
      new Source() {
        @Override
        public List<Script> scripts(Dialect dialect) {
          return load(dialect);
        }

        @Override
        public String read(String path) {
          return Migrations.read(path);
        }
      };

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
    return apply(dataSource, dialect, CLASSPATH);
  }

  /** Wie {@link #apply(DataSource, Dialect)} mit austauschbarer Quelle (fuer Tests). */
  static int apply(DataSource dataSource, Dialect dialect, Source source) throws SQLException {
    List<Script> scripts = new ArrayList<>(source.scripts(dialect));
    // Stabil nach Version: gleiche Version behaelt die Reihenfolge der Quelle (common vor Dialekt).
    scripts.sort(java.util.Comparator.comparingInt(Script::version));
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(true);
      return switch (dialect) {
        case SQLITE -> run(c, scripts, dialect, source);
        case MARIADB -> applyMariaDb(c, scripts, source);
      };
    }
  }

  static Dialect detect(Connection c) throws SQLException {
    String product = c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
    if (product.contains("sqlite")) {
      return Dialect.SQLITE;
    }
    if (product.contains("mariadb")) {
      return Dialect.MARIADB;
    }
    if (product.contains("mysql")) {
      // Die Schema-Skripte nutzen CREATE INDEX IF NOT EXISTS, das MySQL nicht kennt. Lieber klar
      // abbrechen als halb migrieren.
      throw new MigrationException(
          "MySQL is not supported. Use MariaDB 10.6 or newer (storage.type: mariadb) or SQLite.");
    }
    throw new MigrationException("unsupported database: " + product);
  }

  private static int applyMariaDb(Connection c, List<Script> scripts, Source source)
      throws SQLException {
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
      return run(c, scripts, Dialect.MARIADB, source);
    } finally {
      try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        ps.setString(1, LOCK_NAME);
        ps.execute();
      }
    }
  }

  private static int run(Connection c, List<Script> scripts, Dialect dialect, Source source)
      throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS schema_version ("
              + "version INT NOT NULL, scope VARCHAR(16) NOT NULL, name VARCHAR(190) NOT NULL, "
              + "checksum CHAR(64) NOT NULL, applied_at BIGINT NOT NULL, "
              + "PRIMARY KEY (version, scope))");
    }
    int count = 0;
    for (Script script : scripts) {
      String content = source.read(script.path());
      String checksum = sha256(content);
      boolean applied =
          dialect == Dialect.SQLITE
              ? applyStepInTransaction(c, script, content, checksum, dialect)
              : applyStep(c, script, content, checksum, dialect);
      if (applied) {
        count++;
      }
    }
    return count;
  }

  /**
   * SQLite: ein Schritt gleich eine Transaktion, geprueft und geschrieben unter dem Schreibsperre.
   */
  private static boolean applyStepInTransaction(
      Connection c, Script script, String content, String checksum, Dialect dialect)
      throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("BEGIN IMMEDIATE");
    }
    try {
      boolean applied = applyStep(c, script, content, checksum, dialect);
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

  /** Prueft den Stand in {@code schema_version} und wendet den Schritt bei Bedarf an. */
  private static boolean applyStep(
      Connection c, Script script, String content, String checksum, Dialect dialect)
      throws SQLException {
    String known = null;
    try (PreparedStatement ps =
        c.prepareStatement("SELECT checksum FROM schema_version WHERE version = ? AND scope = ?")) {
      ps.setInt(1, script.version());
      ps.setString(2, script.scope());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          known = rs.getString(1);
        }
      }
    }
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
      return false;
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
            "INSERT INTO schema_version(version, scope, name, checksum, applied_at) "
                + "VALUES (?,?,?,?,?)")) {
      ps.setInt(1, script.version());
      ps.setString(2, script.scope());
      ps.setString(3, script.name());
      ps.setString(4, checksum);
      ps.setLong(5, System.currentTimeMillis());
      ps.executeUpdate();
    }
    return true;
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
