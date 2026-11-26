package dev.orderhub.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

final class JdbcAuditRepository implements AuditRepository {

  private final Jdbc jdbc;

  JdbcAuditRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  @Override
  public void append(Entry e) {
    jdbc.update(
        "INSERT INTO audit_log(actor, action, target, reason, detail, server_id, created_at) "
            + "VALUES (?,?,?,?,?,?,?)",
        cut(e.actor(), 64),
        cut(e.action(), 64),
        cut(e.target(), 190),
        cut(e.reason(), 500),
        cut(e.detail(), 4000),
        cut(e.serverId(), 64),
        e.createdAt());
  }

  @Override
  public List<Entry> recent(int limit) {
    return jdbc.list(
        "SELECT actor, action, target, reason, detail, server_id, created_at FROM audit_log "
            + "ORDER BY id DESC LIMIT ?",
        JdbcAuditRepository::map,
        Math.max(1, Math.min(limit, 200)));
  }

  private static Entry map(ResultSet rs) throws SQLException {
    return new Entry(
        rs.getString("actor"),
        rs.getString("action"),
        rs.getString("target"),
        rs.getString("reason"),
        rs.getString("detail"),
        rs.getString("server_id"),
        rs.getLong("created_at"));
  }
}
