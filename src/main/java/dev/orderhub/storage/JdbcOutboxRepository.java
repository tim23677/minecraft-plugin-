package dev.orderhub.storage;

import java.util.List;

final class JdbcOutboxRepository implements OutboxRepository {

  private final Jdbc jdbc;

  JdbcOutboxRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  @Override
  public void enqueueDiscord(String payload, long now) {
    jdbc.update(
        "INSERT INTO discord_outbox(payload, state, attempts, next_attempt_at, created_at, updated_at) "
            + "VALUES (?, 'PENDING', 0, ?, ?, ?)",
        payload,
        now,
        now,
        now);
  }

  @Override
  public List<DiscordItem> dueDiscord(long now, int limit) {
    return jdbc.list(
        "SELECT id, payload, attempts FROM discord_outbox WHERE state = 'PENDING' "
            + "AND next_attempt_at <= ? AND (claimed_until IS NULL OR claimed_until < ?) "
            + "ORDER BY id ASC LIMIT ?",
        rs -> new DiscordItem(rs.getLong("id"), rs.getString("payload"), rs.getInt("attempts")),
        now,
        now,
        Math.max(1, Math.min(limit, 50)));
  }

  @Override
  public boolean claimDiscord(long id, String claimant, long now, long claimedUntil) {
    return jdbc.update(
            "UPDATE discord_outbox SET claimed_by = ?, claimed_until = ? "
                + "WHERE id = ? AND state = 'PENDING' AND (claimed_until IS NULL OR claimed_until < ?)",
            cut(claimant, 80),
            claimedUntil,
            id,
            now)
        == 1;
  }

  @Override
  public void discordSent(long id, long now) {
    jdbc.update(
        "UPDATE discord_outbox SET state = 'SENT', updated_at = ? WHERE id = ? AND state = 'PENDING'",
        now,
        id);
  }

  @Override
  public void discordRetry(long id, int attempts, long nextAttemptAt, String error, long now) {
    jdbc.update(
        "UPDATE discord_outbox SET attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ?, "
            + "claimed_by = NULL, claimed_until = NULL "
            + "WHERE id = ? AND state = 'PENDING'",
        attempts,
        nextAttemptAt,
        cut(error, 500),
        now,
        id);
  }

  @Override
  public void discordFailed(long id, int attempts, String error, long now) {
    jdbc.update(
        "UPDATE discord_outbox SET state = 'FAILED', attempts = ?, last_error = ?, updated_at = ? "
            + "WHERE id = ? AND state = 'PENDING'",
        attempts,
        cut(error, 500),
        now,
        id);
  }

  @Override
  public void publishCacheEvent(String channel, String payload, String originServer, long now) {
    jdbc.update(
        "INSERT INTO cache_outbox(channel, payload, origin_server, created_at) VALUES (?,?,?,?)",
        cut(channel, 64),
        cut(payload, 2000),
        cut(originServer, 64),
        now);
  }

  @Override
  public List<CacheEvent> cacheEventsAfter(long afterId, String exceptServer, int limit) {
    return jdbc.list(
        "SELECT id, channel, payload, origin_server, created_at FROM cache_outbox "
            + "WHERE id > ? AND origin_server <> ? ORDER BY id ASC LIMIT ?",
        rs ->
            new CacheEvent(
                rs.getLong("id"),
                rs.getString("channel"),
                rs.getString("payload"),
                rs.getString("origin_server"),
                rs.getLong("created_at")),
        afterId,
        exceptServer,
        Math.max(1, Math.min(limit, 500)));
  }

  @Override
  public long latestCacheEventId() {
    return jdbc.one("SELECT COALESCE(MAX(id), 0) AS m FROM cache_outbox", rs -> rs.getLong("m"))
        .orElse(0L);
  }

  @Override
  public int purgeCacheEventsBefore(long cutoff) {
    return jdbc.update("DELETE FROM cache_outbox WHERE created_at < ?", cutoff);
  }
}
