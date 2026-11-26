package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.tx.InvalidTransitionException;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC-Implementierung von {@link JournalRepository}. */
final class JdbcJournalRepository implements JournalRepository {

  private static final int MAX_DETAIL = 1000;
  private static final String SELECT =
      "SELECT id, idempotency_key, order_id, player_uuid, server_id, tx_type, item_key, "
          + "item_format_version, item_data, quantity, amount, state, version, created_at, "
          + "updated_at, detail FROM transaction_journal ";

  private final Jdbc jdbc;

  JdbcJournalRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public InsertResult insertIfAbsent(JournalEntry e) {
    Optional<JournalEntry> existing = findByKey(e.idempotencyKey());
    if (existing.isPresent()) {
      return new InsertResult(existing.get(), false);
    }
    ItemSnapshot item = e.item();
    boolean inserted =
        jdbc.insertUnlessDuplicate(
            "INSERT INTO transaction_journal(id, idempotency_key, order_id, player_uuid, server_id, "
                + "tx_type, item_key, item_format_version, item_data, quantity, amount, state, "
                + "version, created_at, updated_at, detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            e.id(),
            e.idempotencyKey(),
            e.orderId(),
            e.playerId(),
            e.serverId(),
            e.type(),
            item == null ? null : item.itemKey(),
            item == null ? null : item.formatVersion(),
            item == null ? null : item.data(),
            e.quantity(),
            e.amount(),
            e.state(),
            e.version(),
            e.createdAt(),
            e.updatedAt(),
            truncate(e.detail()));
    if (inserted) {
      return new InsertResult(e, true);
    }
    // Parallel von einem anderen Server angelegt: Sperrendes Lesen sieht den neuesten Stand.
    JournalEntry winner =
        jdbc.one(
                SELECT + "WHERE idempotency_key = ?" + jdbc.forUpdate(),
                JdbcJournalRepository::map,
                e.idempotencyKey())
            .orElseThrow(
                () ->
                    new StorageException(
                        "duplicate key but entry not found",
                        new SQLException("duplicate idempotency key without row")));
    return new InsertResult(winner, false);
  }

  @Override
  public Optional<JournalEntry> find(UUID id) {
    return jdbc.one(SELECT + "WHERE id = ?", JdbcJournalRepository::map, id);
  }

  @Override
  public Optional<JournalEntry> findByKey(String idempotencyKey) {
    return jdbc.one(SELECT + "WHERE idempotency_key = ?", JdbcJournalRepository::map, idempotencyKey);
  }

  @Override
  public boolean transition(JournalEntry current, TxState target, String detail, long now) {
    if (!current.state().canTransitionTo(target)) {
      throw new InvalidTransitionException(current.state(), target);
    }
    return jdbc.update(
            "UPDATE transaction_journal SET state = ?, version = version + 1, updated_at = ?, "
                + "detail = ? WHERE id = ? AND state = ? AND version = ?",
            target,
            now,
            truncate(detail),
            current.id(),
            current.state(),
            current.version())
        == 1;
  }

  @Override
  public List<JournalEntry> findByStates(EnumSet<TxState> states, int limit) {
    if (states.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", java.util.Collections.nCopies(states.size(), "?"));
    List<Object> params = new ArrayList<>(states);
    params.add(Math.max(1, limit));
    return jdbc.list(
        SELECT + "WHERE state IN (" + marks + ") ORDER BY updated_at ASC, created_at ASC LIMIT ?",
        JdbcJournalRepository::map,
        params.toArray());
  }

  @Override
  public List<JournalEntry> findByPlayer(UUID player, int offset, int limit) {
    return jdbc.list(
        SELECT + "WHERE player_uuid = ? ORDER BY created_at DESC, id ASC LIMIT ? OFFSET ?",
        JdbcJournalRepository::map,
        player,
        Math.max(1, limit),
        Math.max(0, offset));
  }

  @Override
  public List<JournalEntry> findByOrder(long orderId) {
    return jdbc.list(
        SELECT + "WHERE order_id = ? ORDER BY created_at ASC, id ASC",
        JdbcJournalRepository::map,
        orderId);
  }

  @Override
  public List<JournalEntry> findRecent(int offset, int limit) {
    return jdbc.list(
        SELECT + "ORDER BY created_at DESC, id ASC LIMIT ? OFFSET ?",
        JdbcJournalRepository::map,
        Math.max(1, limit),
        Math.max(0, offset));
  }

  private static String truncate(String detail) {
    return detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
  }

  static JournalEntry map(ResultSet rs) throws SQLException {
    String itemKey = rs.getString("item_key");
    ItemSnapshot item =
        itemKey == null
            ? null
            : new ItemSnapshot(itemKey, rs.getInt("item_format_version"), rs.getBytes("item_data"));
    return new JournalEntry(
        Jdbc.uuid(rs, "id"),
        rs.getString("idempotency_key"),
        Jdbc.nullableLong(rs, "order_id"),
        Jdbc.uuid(rs, "player_uuid"),
        rs.getString("server_id"),
        TxType.valueOf(rs.getString("tx_type")),
        item,
        rs.getInt("quantity"),
        rs.getLong("amount"),
        TxState.valueOf(rs.getString("state")),
        rs.getLong("version"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"),
        rs.getString("detail"));
  }
}
