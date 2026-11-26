package dev.orderhub.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Duenne PreparedStatement-Helfer. SQL-Texte sind ausschliesslich Konstanten bzw. aus festen
 * Fragmenten (Enum-Mapping, Platzhalter-Listen) zusammengesetzt; Nutzerwerte werden nur gebunden.
 */
final class Jdbc {

  /** Zeilenabbildung. */
  @FunctionalInterface
  interface Mapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private final Connection connection;
  private final Dialect dialect;

  Jdbc(Connection connection, Dialect dialect) {
    this.connection = connection;
    this.dialect = dialect;
  }

  Dialect dialect() {
    return dialect;
  }

  /** {@code " FOR UPDATE"} auf MariaDB, sonst leer. Auch fuer Lesen nach Duplicate-Key. */
  String forUpdate() {
    return dialect.supportsRowLocks() ? " FOR UPDATE" : "";
  }

  private PreparedStatement prepare(String sql, boolean keys, Object... params)
      throws SQLException {
    PreparedStatement ps =
        keys
            ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            : connection.prepareStatement(sql);
    try {
      for (int i = 0; i < params.length; i++) {
        bind(ps, i + 1, params[i]);
      }
      return ps;
    } catch (SQLException | RuntimeException e) {
      ps.close();
      throw e;
    }
  }

  private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
    if (value instanceof UUID u) {
      ps.setString(index, u.toString());
    } else if (value instanceof Enum<?> e) {
      ps.setString(index, e.name());
    } else if (value instanceof byte[] b) {
      ps.setBytes(index, b);
    } else if (value instanceof Boolean b) {
      ps.setInt(index, b ? 1 : 0);
    } else {
      ps.setObject(index, value);
    }
  }

  <T> List<T> list(String sql, Mapper<T> mapper, Object... params) {
    try (PreparedStatement ps = prepare(sql, false, params);
        ResultSet rs = ps.executeQuery()) {
      List<T> out = new ArrayList<>();
      while (rs.next()) {
        out.add(mapper.map(rs));
      }
      return out;
    } catch (SQLException e) {
      throw new StorageException(e);
    }
  }

  <T> Optional<T> one(String sql, Mapper<T> mapper, Object... params) {
    List<T> rows = list(sql, mapper, params);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  long count(String sql, Object... params) {
    return one(sql, rs -> rs.getLong(1), params).orElse(0L);
  }

  int update(String sql, Object... params) {
    try (PreparedStatement ps = prepare(sql, false, params)) {
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new StorageException(e);
    }
  }

  /** Fuehrt INSERT aus und liefert den generierten Schluessel. */
  long insertReturningId(String sql, Object... params) {
    try (PreparedStatement ps = prepare(sql, true, params)) {
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (!rs.next()) {
          throw new StorageException("no generated key", new SQLException("no generated key"));
        }
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new StorageException(e);
    }
  }

  /** INSERT, der bei UNIQUE-Konflikt {@code false} liefert (Transaktion bleibt benutzbar). */
  boolean insertUnlessDuplicate(String sql, Object... params) {
    try (PreparedStatement ps = prepare(sql, false, params)) {
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (isUniqueViolation(e)) {
        return false;
      }
      throw new StorageException(e);
    }
  }

  static boolean isUniqueViolation(SQLException e) {
    if (e.getErrorCode() == 1062) {
      return true;
    }
    String msg = e.getMessage();
    return msg != null
        && (msg.contains("UNIQUE constraint failed") || msg.contains("PRIMARY KEY must be unique"));
  }

  static UUID uuid(ResultSet rs, String column) throws SQLException {
    String s = rs.getString(column);
    return s == null ? null : UUID.fromString(s);
  }

  static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }
}
