package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC-Implementierung von {@link OrderRepository}. */
final class JdbcOrderRepository implements OrderRepository {

  private static final int OPTIONS_BATCH = 500;

  private static final String SELECT =
      "SELECT o.id, o.owner_uuid, o.server_id, o.item_key, i.format_version, i.item_data, "
          + "o.unit_price, o.quantity_total, o.quantity_filled, o.min_delivery, "
          + "o.escrow_remaining, o.status, o.created_at, o.expires_at, o.updated_at, "
          + "o.version, o.auto_renew, o.category "
          + "FROM orders o JOIN order_items i ON i.order_id = o.id ";

  private final Jdbc jdbc;

  JdbcOrderRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Order insert(NewOrder o, long createdAt) {
    long escrow = o.escrow();
    long id =
        jdbc.insertReturningId(
            "INSERT INTO orders(owner_uuid, server_id, item_key, unit_price, quantity_total, "
                + "quantity_filled, min_delivery, escrow_remaining, status, created_at, "
                + "expires_at, updated_at, version, auto_renew, category) "
                + "VALUES (?,?,?,?,?,0,?,?,?,?,?,?,0,?,?)",
            o.owner(),
            o.serverId(),
            o.item().itemKey(),
            o.unitPrice(),
            o.quantity(),
            o.minDelivery(),
            escrow,
            OrderStatus.ACTIVE,
            createdAt,
            o.expiresAt(),
            createdAt,
            o.autoRenew(),
            o.category());
    jdbc.update(
        "INSERT INTO order_items(order_id, item_key, format_version, item_data) VALUES (?,?,?,?)",
        id,
        o.item().itemKey(),
        o.item().formatVersion(),
        o.item().data());
    adjustEscrow(o.owner(), escrow, createdAt);
    return new Order(
        id,
        o.owner(),
        o.serverId(),
        o.item(),
        o.unitPrice(),
        o.quantity(),
        0,
        o.minDelivery(),
        escrow,
        OrderStatus.ACTIVE,
        createdAt,
        o.expiresAt(),
        createdAt,
        0,
        o.autoRenew(),
        o.category());
  }

  @Override
  public Optional<Order> find(long id) {
    return jdbc.one(SELECT + "WHERE o.id = ?", JdbcOrderRepository::map, id);
  }

  @Override
  public Optional<Order> findForUpdate(long id) {
    return jdbc.one(SELECT + "WHERE o.id = ?" + jdbc.forUpdate(), JdbcOrderRepository::map, id);
  }

  @Override
  public boolean update(Order next) {
    if (next.version() < 1) {
      throw new IllegalArgumentException("next.version must be >= 1");
    }
    Optional<long[]> prev =
        jdbc.one(
            "SELECT escrow_remaining FROM orders WHERE id = ?" + jdbc.forUpdate(),
            rs -> new long[] {rs.getLong(1)},
            next.id());
    if (prev.isEmpty()) {
      return false;
    }
    int rows =
        jdbc.update(
            "UPDATE orders SET unit_price = ?, quantity_total = ?, quantity_filled = ?, "
                + "min_delivery = ?, escrow_remaining = ?, status = ?, expires_at = ?, "
                + "updated_at = ?, version = ?, auto_renew = ?, category = ? "
                + "WHERE id = ? AND version = ?",
            next.unitPrice(),
            next.quantityTotal(),
            next.quantityFilled(),
            next.minDelivery(),
            next.escrowRemaining(),
            next.status(),
            next.expiresAt(),
            next.updatedAt(),
            next.version(),
            next.autoRenew(),
            next.category(),
            next.id(),
            next.version() - 1);
    if (rows != 1) {
      return false;
    }
    adjustEscrow(next.owner(), next.escrowRemaining() - prev.get()[0], next.updatedAt());
    return true;
  }

  /** Haelt {@code escrow_accounts} in derselben Transaktion synchron zu den Auftraegen. */
  private void adjustEscrow(UUID owner, long delta, long now) {
    if (delta == 0) {
      return;
    }
    String sql =
        "UPDATE escrow_accounts SET reserved = reserved + ?, updated_at = ? WHERE player_uuid = ?";
    if (jdbc.update(sql, delta, now, owner) == 1) {
      return;
    }
    boolean inserted =
        jdbc.insertUnlessDuplicate(
            "INSERT INTO escrow_accounts(player_uuid, reserved, updated_at) VALUES (?,?,?)",
            owner,
            delta,
            now);
    if (!inserted) {
      jdbc.update(sql, delta, now, owner);
    }
  }

  @Override
  public List<Order> search(OrderQuery q) {
    Filter f = filter(q);
    List<Object> params = new ArrayList<>(f.params());
    String where = f.where();
    if (q.after() != null) {
      where += " AND " + keysetCondition(q.sort(), params, q.after());
    }
    params.add(q.limit());
    String sql = SELECT + where + " ORDER BY " + orderBy(q.sort()) + " LIMIT ?";
    if (q.after() == null) {
      params.add(q.offset());
      sql += " OFFSET ?";
    }
    return jdbc.list(sql, JdbcOrderRepository::map, params.toArray());
  }

  /**
   * Bedingung "Zeile liegt hinter dem Cursor" je Sortierung. Feste Fragmente, Werte werden
   * gebunden. Reihenfolge der Parameter entspricht der Reihenfolge der Platzhalter.
   */
  private static String keysetCondition(
      OrderQuery.Sort sort, List<Object> params, OrderQuery.Cursor c) {
    switch (sort) {
      case NEWEST -> {
        params.add(c.k1());
        params.add(c.k1());
        params.add(c.id());
        return "(o.created_at < ? OR (o.created_at = ? AND o.id < ?))";
      }
      case HIGHEST_PRICE -> {
        params.add(c.k1());
        params.add(c.k1());
        params.add(c.id());
        return "(o.unit_price < ? OR (o.unit_price = ? AND o.id > ?))";
      }
      case LOWEST_PRICE -> {
        params.add(c.k1());
        params.add(c.k1());
        params.add(c.id());
        return "(o.unit_price > ? OR (o.unit_price = ? AND o.id > ?))";
      }
      case ALMOST_COMPLETED -> {
        params.add(c.k1());
        params.add(c.k1());
        params.add(c.id());
        return "(o.fill_permille < ? OR (o.fill_permille = ? AND o.id > ?))";
      }
      case HIGHEST_DEMAND -> {
        params.add(c.k1());
        params.add(c.k1());
        params.add(c.k2());
        params.add(c.k1());
        params.add(c.k2());
        params.add(c.id());
        return "(o.remaining_qty < ? OR (o.remaining_qty = ? AND o.escrow_remaining < ?) "
            + "OR (o.remaining_qty = ? AND o.escrow_remaining = ? AND o.id > ?))";
      }
      default -> throw new IllegalArgumentException("sort");
    }
  }

  @Override
  public long count(OrderQuery q) {
    Filter f = filter(q);
    return jdbc.count("SELECT COUNT(*) FROM orders o " + f.where(), f.params().toArray());
  }

  @Override
  public void lockOwner(UUID owner) {
    if (!jdbc.dialect().supportsRowLocks()) {
      return;
    }
    jdbc.update(
        "INSERT INTO escrow_accounts(player_uuid, reserved, updated_at) VALUES (?,0,?) "
            + "ON DUPLICATE KEY UPDATE player_uuid = player_uuid",
        owner,
        0L);
    jdbc.list(
        "SELECT player_uuid FROM escrow_accounts WHERE player_uuid = ? FOR UPDATE",
        rs -> rs.getString(1),
        owner);
  }

  @Override
  public int countOpenByOwnerUnlocked(UUID owner) {
    return (int)
        jdbc.count(
            "SELECT COUNT(*) FROM orders WHERE owner_uuid = ? AND status IN ('ACTIVE','PAUSED')",
            owner);
  }

  @Override
  public int countOpenByOwner(UUID owner) {
    return (int)
        jdbc.count(
            "SELECT COUNT(*) FROM orders WHERE owner_uuid = ? AND status IN ('ACTIVE','PAUSED')"
                // Gesperrtes Lesen sieht den neuesten Stand, auch bei REPEATABLE READ mit altem
                // Snapshot.
                + (jdbc.dialect() == Dialect.MARIADB ? " FOR UPDATE" : ""),
            owner);
  }

  @Override
  public int countExpiringBetween(UUID owner, long from, long until) {
    return (int)
        jdbc.count(
            "SELECT COUNT(*) FROM orders WHERE owner_uuid = ? AND status = 'ACTIVE' "
                + "AND expires_at > ? AND expires_at <= ?",
            owner,
            from,
            until);
  }

  @Override
  public List<Order> findExpired(long now, int limit) {
    return jdbc.list(
        SELECT
            + "WHERE o.status = 'ACTIVE' AND o.expires_at <= ? ORDER BY o.expires_at, o.id LIMIT ?",
        JdbcOrderRepository::map,
        now,
        Math.max(1, limit));
  }

  private record Filter(String where, List<Object> params) {}

  /** Baut die WHERE-Klausel aus festen Fragmenten, Werte werden gebunden. */
  private static Filter filter(OrderQuery q) {
    StringBuilder where = new StringBuilder("WHERE o.status = ?");
    List<Object> params = new ArrayList<>();
    params.add(q.status());
    if (q.owner() != null) {
      where.append(" AND o.owner_uuid = ?");
      params.add(q.owner());
    }
    if (q.itemKey() != null) {
      where.append(" AND o.item_key = ?");
      params.add(q.itemKey());
    }
    if (q.category() != null) {
      where.append(" AND o.category = ?");
      params.add(q.category());
    }
    if (q.minUnitPrice() != null) {
      where.append(" AND o.unit_price >= ?");
      params.add(q.minUnitPrice());
    }
    if (q.maxUnitPrice() != null) {
      where.append(" AND o.unit_price <= ?");
      params.add(q.maxUnitPrice());
    }
    if (q.text() != null) {
      // Suchtext ist auf [a-z0-9_] normalisiert, Unterstrich ist im LIKE ein Platzhalter und wird
      // maskiert.
      where.append(" AND o.item_key LIKE ? ESCAPE '!'");
      params.add("%" + q.text().replace("!", "!!").replace("_", "!_") + "%");
    }
    return new Filter(where.toString(), params);
  }

  /** Enum-Mapping auf feste SQL-Fragmente, nie Nutzertext. */
  static String orderBy(OrderQuery.Sort sort) {
    return switch (sort) {
      case NEWEST -> "o.created_at DESC, o.id DESC";
      case HIGHEST_PRICE -> "o.unit_price DESC, o.id ASC";
      case LOWEST_PRICE -> "o.unit_price ASC, o.id ASC";
      case ALMOST_COMPLETED -> "o.fill_permille DESC, o.id ASC";
      case HIGHEST_DEMAND -> "o.remaining_qty DESC, o.escrow_remaining DESC, o.id ASC";
    };
  }

  @Override
  public OrderOptions options(long orderId) {
    return jdbc.one(
            "SELECT tol_durability, tol_ench_min, auto_claim FROM order_options WHERE order_id = ?",
            rs -> new OrderOptions(rs.getInt(1), rs.getInt(2) != 0, rs.getInt(3) != 0),
            orderId)
        .orElse(OrderOptions.EXACT);
  }

  @Override
  public java.util.Map<Long, OrderOptions> optionsFor(java.util.Collection<Long> orderIds) {
    java.util.Map<Long, OrderOptions> out = new java.util.HashMap<>();
    if (orderIds.isEmpty()) {
      return out;
    }
    List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(orderIds));
    for (int from = 0; from < ids.size(); from += OPTIONS_BATCH) {
      List<Long> part = ids.subList(from, Math.min(ids.size(), from + OPTIONS_BATCH));
      String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
      jdbc.list(
              "SELECT order_id, tol_durability, tol_ench_min, auto_claim FROM order_options "
                  + "WHERE order_id IN ("
                  + marks
                  + ")",
              rs ->
                  java.util.Map.entry(
                      rs.getLong(1),
                      new OrderOptions(rs.getInt(2), rs.getInt(3) != 0, rs.getInt(4) != 0)),
              part.toArray())
          .forEach(e -> out.put(e.getKey(), e.getValue()));
    }
    return out;
  }

  @Override
  public void saveOptions(long orderId, OrderOptions options) {
    jdbc.update("DELETE FROM order_options WHERE order_id = ?", orderId);
    jdbc.update(
        "INSERT INTO order_options(order_id, tol_durability, tol_ench_min, auto_claim) "
            + "VALUES (?,?,?,?)",
        orderId,
        options.minDurabilityPercent(),
        options.enchantmentsAtLeast(),
        options.autoClaim());
  }

  @Override
  public List<CategoryRow> findLegacyCategories(long afterId, int limit) {
    return jdbc.list(
        "SELECT id, item_key, category FROM orders WHERE id > ? "
            + "AND status IN ('ACTIVE','PAUSED') AND category IN ('blocks','food','items') "
            + "ORDER BY id LIMIT ?",
        rs -> new CategoryRow(rs.getLong(1), rs.getString(2), rs.getString(3)),
        afterId,
        Math.max(1, limit));
  }

  @Override
  public void setCategory(long orderId, String category) {
    jdbc.update("UPDATE orders SET category = ? WHERE id = ?", category, orderId);
  }

  static Order map(ResultSet rs) throws SQLException {
    ItemSnapshot item =
        new ItemSnapshot(
            rs.getString("item_key"), rs.getInt("format_version"), rs.getBytes("item_data"));
    return new Order(
        rs.getLong("id"),
        Jdbc.uuid(rs, "owner_uuid"),
        rs.getString("server_id"),
        item,
        rs.getLong("unit_price"),
        rs.getInt("quantity_total"),
        rs.getInt("quantity_filled"),
        rs.getInt("min_delivery"),
        rs.getLong("escrow_remaining"),
        OrderStatus.valueOf(rs.getString("status")),
        rs.getLong("created_at"),
        rs.getLong("expires_at"),
        rs.getLong("updated_at"),
        rs.getLong("version"),
        rs.getInt("auto_renew") != 0,
        rs.getString("category"));
  }
}
