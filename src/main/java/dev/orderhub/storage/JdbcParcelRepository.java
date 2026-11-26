package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-Implementierung von {@link ParcelRepository} fuer {@code item_claims} und {@code
 * item_returns}.
 */
final class JdbcParcelRepository implements ParcelRepository {

  private static final String COLUMNS =
      "id, owner_uuid, order_id, item_key, item_format_version, item_data, quantity, state, "
          + "reserved_by, source_tx, created_at, updated_at";

  private final Jdbc jdbc;
  private final String table;

  /** {@code table} ist eine interne Konstante ({@code item_claims} oder {@code item_returns}). */
  JdbcParcelRepository(Jdbc jdbc, String table) {
    if (!table.equals("item_claims") && !table.equals("item_returns")) {
      throw new IllegalArgumentException("unknown parcel table");
    }
    this.jdbc = jdbc;
    this.table = table;
  }

  @Override
  public long add(
      UUID owner, Long orderId, ItemSnapshot item, int quantity, UUID sourceTx, long now) {
    if (quantity < 0) {
      throw new IllegalArgumentException("quantity");
    }
    Optional<Long> existing = existingId(sourceTx, owner, false);
    if (existing.isPresent()) {
      return existing.get();
    }
    boolean inserted =
        jdbc.insertUnlessDuplicate(
            "INSERT INTO "
                + table
                + "(owner_uuid, order_id, item_key, item_format_version, item_data, quantity, state, "
                + "reserved_by, source_tx, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,'AVAILABLE',NULL,?,?,?)",
            owner,
            orderId,
            item.itemKey(),
            item.formatVersion(),
            item.data(),
            quantity,
            sourceTx,
            now,
            now);
    if (inserted) {
      return existingId(sourceTx, owner, false).orElseThrow();
    }
    // Paralleler Insert eines anderen Servers: sperrendes Lesen sieht den neuesten Stand.
    return existingId(sourceTx, owner, true)
        .orElseThrow(() ->
            new StorageException(
                "duplicate parcel but row not found", new SQLException("duplicate without row")));
  }

  private Optional<Long> existingId(UUID sourceTx, UUID owner, boolean lock) {
    return jdbc.one(
        "SELECT id FROM "
            + table
            + " WHERE source_tx = ? AND owner_uuid = ?"
            + (lock ? jdbc.forUpdate() : ""),
        rs -> rs.getLong(1),
        sourceTx,
        owner);
  }

  @Override
  public Optional<Parcel> find(long id) {
    return jdbc.one(
        "SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?", JdbcParcelRepository::map, id);
  }

  @Override
  public List<Parcel> listAvailable(UUID owner, int offset, int limit) {
    return jdbc.list(
        "SELECT "
            + COLUMNS
            + " FROM "
            + table
            + " WHERE owner_uuid = ? AND state = 'AVAILABLE' AND quantity > 0 "
            + "ORDER BY id ASC LIMIT ? OFFSET ?",
        JdbcParcelRepository::map,
        owner,
        Math.max(1, limit),
        Math.max(0, offset));
  }

  @Override
  public int countAvailable(UUID owner) {
    return (int)
        jdbc.count(
            "SELECT COUNT(*) FROM "
                + table
                + " WHERE owner_uuid = ? AND state = 'AVAILABLE' AND quantity > 0",
            owner);
  }

  @Override
  public boolean reserve(long id, UUID tx, long now) {
    return jdbc.update(
            "UPDATE "
                + table
                + " SET state = 'RESERVED', reserved_by = ?, updated_at = ? "
                + "WHERE id = ? AND state = 'AVAILABLE' AND quantity > 0",
            tx,
            now,
            id)
        == 1;
  }

  @Override
  public boolean settle(long id, UUID tx, int transferred, long now) {
    if (transferred < 0) {
      throw new IllegalArgumentException("transferred");
    }
    // Reihenfolge der SET-Klauseln ist wichtig: MariaDB wertet links nach rechts mit bereits
    // geaenderten Spalten aus, daher wird quantity zuletzt zugewiesen.
    return jdbc.update(
            "UPDATE "
                + table
                + " SET state = CASE WHEN quantity - ? > 0 THEN 'AVAILABLE' ELSE 'CLAIMED' END, "
                + "reserved_by = NULL, updated_at = ?, quantity = quantity - ? "
                + "WHERE id = ? AND state = 'RESERVED' AND reserved_by = ? AND quantity >= ?",
            transferred,
            now,
            transferred,
            id,
            tx,
            transferred)
        == 1;
  }

  @Override
  public boolean releaseReservation(long id, UUID tx, long now) {
    return jdbc.update(
            "UPDATE "
                + table
                + " SET state = 'AVAILABLE', reserved_by = NULL, updated_at = ? "
                + "WHERE id = ? AND state = 'RESERVED' AND reserved_by = ?",
            now,
            id,
            tx)
        == 1;
  }

  @Override
  public List<Parcel> findReserved(int limit) {
    return jdbc.list(
        "SELECT "
            + COLUMNS
            + " FROM "
            + table
            + " WHERE state = 'RESERVED' ORDER BY updated_at ASC, id ASC LIMIT ?",
        JdbcParcelRepository::map,
        Math.max(1, limit));
  }

  private static Parcel map(ResultSet rs) throws SQLException {
    String reserved = rs.getString("reserved_by");
    return new Parcel(
        rs.getLong("id"),
        Jdbc.uuid(rs, "owner_uuid"),
        Jdbc.nullableLong(rs, "order_id"),
        new ItemSnapshot(
            rs.getString("item_key"), rs.getInt("item_format_version"), rs.getBytes("item_data")),
        rs.getInt("quantity"),
        State.valueOf(rs.getString("state")),
        reserved == null ? null : UUID.fromString(reserved),
        Jdbc.uuid(rs, "source_tx"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"));
  }
}
