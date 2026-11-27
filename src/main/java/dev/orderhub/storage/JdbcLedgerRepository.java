package dev.orderhub.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** JDBC-Implementierung von {@link LedgerRepository}. Nur INSERT und SELECT. */
final class JdbcLedgerRepository implements LedgerRepository {

  private static final int MAX_NOTE = 500;
  private static final String SELECT =
      "SELECT id, tx_id, player_uuid, kind, amount, created_at, note FROM transactions ";

  private final Jdbc jdbc;

  JdbcLedgerRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(Entry e) {
    String note =
        e.note() != null && e.note().length() > MAX_NOTE
            ? e.note().substring(0, MAX_NOTE)
            : e.note();
    jdbc.update(
        "INSERT INTO transactions(id, tx_id, player_uuid, kind, amount, created_at, note) "
            + "VALUES (?,?,?,?,?,?,?)",
        e.id(),
        e.txId(),
        e.player(),
        e.kind(),
        e.amount(),
        e.createdAt(),
        note);
  }

  @Override
  public List<Entry> findByTx(UUID txId) {
    return jdbc.list(SELECT + "WHERE tx_id = ? ORDER BY seq ASC", JdbcLedgerRepository::map, txId);
  }

  @Override
  public List<Entry> findAll(int offset, int limit) {
    return jdbc.list(
        SELECT + "ORDER BY seq ASC LIMIT ? OFFSET ?",
        JdbcLedgerRepository::map,
        Math.max(1, limit),
        Math.max(0, offset));
  }

  @Override
  public List<Entry> findByPlayer(UUID player, int offset, int limit) {
    return jdbc.list(
        SELECT + "WHERE player_uuid = ? ORDER BY seq DESC LIMIT ? OFFSET ?",
        JdbcLedgerRepository::map,
        player,
        Math.max(1, limit),
        Math.max(0, offset));
  }

  private static Entry map(ResultSet rs) throws SQLException {
    return new Entry(
        Jdbc.uuid(rs, "id"),
        Jdbc.uuid(rs, "tx_id"),
        Jdbc.uuid(rs, "player_uuid"),
        Kind.valueOf(rs.getString("kind")),
        rs.getLong("amount"),
        rs.getLong("created_at"),
        rs.getString("note"));
  }
}
