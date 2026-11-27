package dev.orderhub.storage;

import dev.orderhub.market.OpenOrderView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class JdbcInsightsRepository implements InsightsRepository {

  private final Jdbc jdbc;

  JdbcInsightsRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  /** Upsert fuer Zaehler: legt die Zeile an oder addiert auf die vorhandene. */
  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification =
          "Spaltennamen sind ausschliesslich feste Literale der Aufrufer in dieser Klasse, alle Werte werden gebunden")
  private void bump(UUID player, long now, String... columnsAndDeltas) {
    // columnsAndDeltas: Paare "spalte", "wert"; Spaltennamen sind feste Literale dieser Klasse.
    StringBuilder cols = new StringBuilder("player_uuid, last_active_at, updated_at");
    StringBuilder vals = new StringBuilder("?, ?, ?");
    StringBuilder upd = new StringBuilder();
    List<Object> params = new ArrayList<>(List.of(player, now, now));
    for (int i = 0; i < columnsAndDeltas.length; i += 2) {
      String col = columnsAndDeltas[i];
      cols.append(", ").append(col);
      vals.append(", ?");
      params.add(Long.parseLong(columnsAndDeltas[i + 1]));
      if (jdbc.dialect() == Dialect.SQLITE) {
        upd.append(", ").append(col).append(" = ").append(col).append(" + excluded.").append(col);
      } else {
        upd.append(", ")
            .append(col)
            .append(" = ")
            .append(col)
            .append(" + VALUES(")
            .append(col)
            .append(")");
      }
    }
    String tail =
        jdbc.dialect() == Dialect.SQLITE
            ? " ON CONFLICT(player_uuid) DO UPDATE SET last_active_at = excluded.last_active_at,"
                + " updated_at = excluded.updated_at"
            : " ON DUPLICATE KEY UPDATE last_active_at = VALUES(last_active_at),"
                + " updated_at = VALUES(updated_at)";
    jdbc.update(
        "INSERT INTO player_statistics(" + cols + ") VALUES (" + vals + ")" + tail + upd,
        params.toArray());
  }

  @Override
  public void recordOrderCreated(UUID player, long now) {
    bump(player, now, "orders_created", "1");
  }

  @Override
  public void recordOrderCompleted(UUID player, long now) {
    bump(player, now, "orders_completed", "1");
  }

  @Override
  public void recordDelivery(
      UUID supplier, UUID buyer, int quantity, long gross, long net, long now) {
    bump(
        supplier,
        now,
        "items_delivered",
        Long.toString(quantity),
        "money_earned",
        Long.toString(net));
    bump(
        buyer, now, "items_received", Long.toString(quantity), "money_spent", Long.toString(gross));
  }

  @Override
  public Optional<PlayerStats> stats(UUID player) {
    return jdbc.one(
        "SELECT player_uuid, orders_created, orders_completed, items_delivered, items_received, "
            + "money_earned, money_spent FROM player_statistics WHERE player_uuid = ?",
        rs ->
            new PlayerStats(
                Jdbc.uuid(rs, "player_uuid"),
                rs.getLong("orders_created"),
                rs.getLong("orders_completed"),
                rs.getLong("items_delivered"),
                rs.getLong("items_received"),
                rs.getLong("money_earned"),
                rs.getLong("money_spent")),
        player);
  }

  @Override
  public boolean recordTrade(
      UUID journalId, long orderId, String itemKey, long unitPrice, int quantity, long now) {
    return jdbc.insertUnlessDuplicate(
        "INSERT INTO price_history(journal_id, order_id, item_key, unit_price, quantity, created_at) "
            + "VALUES (?,?,?,?,?,?)",
        journalId,
        orderId,
        itemKey,
        unitPrice,
        quantity,
        now);
  }

  @Override
  public List<TradeRow> tradesSince(String itemKey, long since, int limit) {
    return jdbc.list(
        "SELECT journal_id, order_id, item_key, unit_price, quantity, created_at FROM price_history "
            + "WHERE item_key = ? AND created_at >= ? ORDER BY created_at DESC LIMIT ?",
        JdbcInsightsRepository::trade,
        itemKey,
        since,
        Math.max(1, Math.min(limit, 5000)));
  }

  private static TradeRow trade(ResultSet rs) throws SQLException {
    return new TradeRow(
        Jdbc.uuid(rs, "journal_id"),
        rs.getLong("order_id"),
        rs.getString("item_key"),
        rs.getLong("unit_price"),
        rs.getInt("quantity"),
        rs.getLong("created_at"));
  }

  @Override
  public List<OpenOrderView> openDemand(String itemKey, int limit) {
    return jdbc.list(
        "SELECT unit_price, (quantity_total - quantity_filled) AS remaining FROM orders "
            + "WHERE status = 'ACTIVE' AND item_key = ? LIMIT ?",
        rs -> new OpenOrderView(rs.getLong("unit_price"), rs.getInt("remaining")),
        itemKey,
        Math.max(1, Math.min(limit, 5000)));
  }

  @Override
  public List<String> activeItemKeys(int limit) {
    return jdbc.list(
        "SELECT item_key, COUNT(*) AS n FROM orders WHERE status = 'ACTIVE' GROUP BY item_key "
            + "ORDER BY n DESC LIMIT ?",
        rs -> rs.getString("item_key"),
        Math.max(1, Math.min(limit, 500)));
  }

  @Override
  public int purgeTradesBefore(long cutoff) {
    return jdbc.update("DELETE FROM price_history WHERE created_at < ?", cutoff);
  }

  @Override
  public boolean addFavorite(UUID player, String itemKey, long now) {
    return jdbc.insertUnlessDuplicate(
        "INSERT INTO player_favorites(player_uuid, item_key, created_at) VALUES (?,?,?)",
        player,
        itemKey,
        now);
  }

  @Override
  public boolean removeFavorite(UUID player, String itemKey) {
    return jdbc.update(
            "DELETE FROM player_favorites WHERE player_uuid = ? AND item_key = ?", player, itemKey)
        == 1;
  }

  @Override
  public List<Favorite> favorites(UUID player) {
    return jdbc.list(
        "SELECT player_uuid, item_key, alert_below_price, last_alert_at FROM player_favorites "
            + "WHERE player_uuid = ? ORDER BY created_at DESC LIMIT 200",
        JdbcInsightsRepository::favorite,
        player);
  }

  private static Favorite favorite(ResultSet rs) throws SQLException {
    long alert = rs.getLong("alert_below_price");
    boolean noAlert = rs.wasNull();
    long last = rs.getLong("last_alert_at");
    boolean noLast = rs.wasNull();
    return new Favorite(
        Jdbc.uuid(rs, "player_uuid"),
        rs.getString("item_key"),
        noAlert ? null : alert,
        noLast ? null : last);
  }

  @Override
  public boolean setAlert(UUID player, String itemKey, Long minPrice) {
    return jdbc.update(
            "UPDATE player_favorites SET alert_below_price = ? WHERE player_uuid = ? AND item_key = ?",
            minPrice,
            player,
            itemKey)
        == 1;
  }

  @Override
  public List<Favorite> alertsFor(String itemKey, long unitPrice, long notAfter) {
    return jdbc.list(
        "SELECT player_uuid, item_key, alert_below_price, last_alert_at FROM player_favorites "
            + "WHERE item_key = ? AND alert_below_price IS NOT NULL AND alert_below_price <= ? "
            + "AND (last_alert_at IS NULL OR last_alert_at <= ?) LIMIT 500",
        JdbcInsightsRepository::favorite,
        itemKey,
        unitPrice,
        notAfter);
  }

  @Override
  public void touchAlert(UUID player, String itemKey, long now) {
    jdbc.update(
        "UPDATE player_favorites SET last_alert_at = ? WHERE player_uuid = ? AND item_key = ?",
        now,
        player,
        itemKey);
  }

  @Override
  public boolean addNote(UUID player, String kind, String payload, String dedupeKey, long now) {
    return jdbc.insertUnlessDuplicate(
        "INSERT INTO notifications(player_uuid, kind, payload, dedupe_key, created_at) "
            + "VALUES (?,?,?,?,?)",
        player,
        kind,
        payload.length() > 2000 ? payload.substring(0, 2000) : payload,
        dedupeKey,
        now);
  }

  @Override
  public List<Note> pendingNotes(UUID player, int limit) {
    return jdbc.list(
        "SELECT id, player_uuid, kind, payload, created_at FROM notifications "
            + "WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id ASC LIMIT ?",
        rs ->
            new Note(
                rs.getLong("id"),
                Jdbc.uuid(rs, "player_uuid"),
                rs.getString("kind"),
                rs.getString("payload"),
                rs.getLong("created_at")),
        player,
        Math.max(1, Math.min(limit, 100)));
  }

  @Override
  public void markNotesDelivered(List<Long> ids, long now) {
    for (long id : ids) {
      jdbc.update("UPDATE notifications SET delivered_at = ? WHERE id = ?", now, id);
    }
  }
}
