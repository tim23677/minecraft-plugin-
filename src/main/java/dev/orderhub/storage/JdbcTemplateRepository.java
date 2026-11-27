package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.domain.OrderTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC-Implementierung der Auftragsvorlagen. Nur PreparedStatements mit gebundenen Werten. */
final class JdbcTemplateRepository implements TemplateRepository {

  private static final String SELECT =
      "SELECT id, owner_uuid, name, item_key, format_version, item_data, unit_price, quantity, "
          + "min_delivery, lifetime_hours, tol_durability, tol_ench_min, auto_renew, auto_claim, "
          + "created_at FROM order_templates ";

  private final Jdbc jdbc;

  JdbcTemplateRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean save(OrderTemplate t, int maxPerPlayer) {
    Optional<OrderTemplate> existing = find(t.owner(), t.name());
    if (existing.isPresent()) {
      jdbc.update(
          "UPDATE order_templates SET item_key = ?, format_version = ?, item_data = ?, "
              + "unit_price = ?, quantity = ?, min_delivery = ?, lifetime_hours = ?, "
              + "tol_durability = ?, tol_ench_min = ?, auto_renew = ?, auto_claim = ? "
              + "WHERE owner_uuid = ? AND name = ?",
          t.item().itemKey(),
          t.item().formatVersion(),
          t.item().data(),
          t.unitPrice(),
          t.quantity(),
          t.minDelivery(),
          t.lifetimeHours(),
          t.options().minDurabilityPercent(),
          t.options().enchantmentsAtLeast(),
          t.autoRenew(),
          t.options().autoClaim(),
          t.owner(),
          t.name());
      return true;
    }
    if (jdbc.count("SELECT COUNT(*) FROM order_templates WHERE owner_uuid = ?", t.owner())
        >= maxPerPlayer) {
      return false;
    }
    jdbc.update(
        "INSERT INTO order_templates(owner_uuid, name, item_key, format_version, item_data, "
            + "unit_price, quantity, min_delivery, lifetime_hours, tol_durability, tol_ench_min, "
            + "auto_renew, auto_claim, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        t.owner(),
        t.name(),
        t.item().itemKey(),
        t.item().formatVersion(),
        t.item().data(),
        t.unitPrice(),
        t.quantity(),
        t.minDelivery(),
        t.lifetimeHours(),
        t.options().minDurabilityPercent(),
        t.options().enchantmentsAtLeast(),
        t.autoRenew(),
        t.options().autoClaim(),
        t.createdAt());
    return true;
  }

  @Override
  public Optional<OrderTemplate> find(UUID owner, String name) {
    return jdbc.one(
        SELECT + "WHERE owner_uuid = ? AND name = ?", JdbcTemplateRepository::map, owner, name);
  }

  @Override
  public List<OrderTemplate> list(UUID owner) {
    return jdbc.list(
        SELECT + "WHERE owner_uuid = ? ORDER BY name", JdbcTemplateRepository::map, owner);
  }

  @Override
  public boolean delete(UUID owner, String name) {
    return jdbc.update("DELETE FROM order_templates WHERE owner_uuid = ? AND name = ?", owner, name)
        > 0;
  }

  private static OrderTemplate map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new OrderTemplate(
        rs.getLong("id"),
        Jdbc.uuid(rs, "owner_uuid"),
        rs.getString("name"),
        new ItemSnapshot(
            rs.getString("item_key"), rs.getInt("format_version"), rs.getBytes("item_data")),
        rs.getLong("unit_price"),
        rs.getInt("quantity"),
        rs.getInt("min_delivery"),
        rs.getInt("lifetime_hours"),
        new OrderOptions(
            rs.getInt("tol_durability"),
            rs.getInt("tol_ench_min") != 0,
            rs.getInt("auto_claim") != 0),
        rs.getInt("auto_renew") != 0,
        rs.getLong("created_at"));
  }
}
