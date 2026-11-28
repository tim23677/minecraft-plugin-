package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import java.util.Optional;
import java.util.UUID;

/** JDBC-Implementierung von {@link HoldRepository}. */
final class JdbcHoldRepository implements HoldRepository {

  private static final String COLUMNS =
      "tx_id, owner_uuid, order_id, item_key, item_format_version, item_data, quantity, state, "
          + "created_at, updated_at";

  private final Jdbc jdbc;

  JdbcHoldRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(UUID txId, UUID owner, Long orderId, ItemSnapshot item, int quantity, long now) {
    jdbc.insertUnlessDuplicate(
        "INSERT INTO delivery_holds(tx_id, owner_uuid, order_id, item_key, item_format_version, "
            + "item_data, quantity, state, created_at, updated_at) VALUES (?,?,?,?,?,?,?,'HELD',?,?)",
        txId,
        owner,
        orderId,
        item.itemKey(),
        item.formatVersion(),
        item.data(),
        quantity,
        now,
        now);
  }

  @Override
  public Optional<Hold> find(UUID txId) {
    return jdbc.one(
        "SELECT " + COLUMNS + " FROM delivery_holds WHERE tx_id = ?",
        rs ->
            new Hold(
                UUID.fromString(rs.getString(1)),
                UUID.fromString(rs.getString(2)),
                rs.getObject(3) == null ? null : rs.getLong(3),
                new ItemSnapshot(rs.getString(4), rs.getInt(5), rs.getBytes(6)),
                rs.getInt(7),
                State.valueOf(rs.getString(8)),
                rs.getLong(9),
                rs.getLong(10)),
        txId);
  }

  @Override
  public boolean finish(UUID txId, State target, long now) {
    if (target == State.HELD) {
      throw new IllegalArgumentException("target");
    }
    return jdbc.update(
            "UPDATE delivery_holds SET state = ?, updated_at = ? WHERE tx_id = ? AND state = 'HELD'",
            target,
            now,
            txId)
        == 1;
  }
}
