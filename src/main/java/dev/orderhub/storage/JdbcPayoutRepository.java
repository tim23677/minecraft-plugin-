package dev.orderhub.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-Implementierung von {@link PayoutRepository} ueber {@code payout_queue}/{@code
 * refund_queue}.
 */
final class JdbcPayoutRepository implements PayoutRepository {

  private static final int MAX_REASON = 500;
  private static final String COLUMNS =
      "id, tx_id, player_uuid, amount, state, attempts, updated_at";

  private final Jdbc jdbc;

  JdbcPayoutRepository(Jdbc jdbc) {
    this.jdbc = jdbc;
  }

  /** Feste Tabellennamen je Kind. */
  private static String table(Kind kind) {
    return switch (kind) {
      case PAYOUT -> "payout_queue";
      case REFUND -> "refund_queue";
    };
  }

  private static final Kind[] KINDS = Kind.values();

  @Override
  public void enqueue(UUID txId, UUID player, Kind kind, long amount, long now) {
    // UNIQUE(tx_id) macht wiederholtes Einreihen wirkungslos.
    jdbc.insertUnlessDuplicate(
        "INSERT INTO "
            + table(kind)
            + "(id, tx_id, player_uuid, amount, state, attempts, created_at, updated_at) "
            + "VALUES (?,?,?,?,'PENDING',0,?,?)",
        UUID.randomUUID(),
        txId,
        player,
        amount,
        now,
        now);
  }

  @Override
  public List<Item> claimBatch(int limit, long now) {
    List<Item> claimed = new ArrayList<>();
    for (Kind kind : KINDS) {
      int missing = limit - claimed.size();
      if (missing <= 0) {
        break;
      }
      claimKind(kind, missing, now, claimed);
    }
    return claimed;
  }

  private void claimKind(Kind kind, int limit, long now, List<Item> out) {
    String table = table(kind);
    List<UUID> candidates =
        jdbc.list(
            "SELECT id FROM "
                + table
                + " WHERE state = 'PENDING' ORDER BY created_at ASC, id ASC LIMIT ?",
            rs -> Jdbc.uuid(rs, "id"),
            limit);
    for (UUID id : candidates) {
      // Compare-and-Set: nur wer PENDING -> IN_PROGRESS schafft, besitzt die Zahlung.
      int rows =
          jdbc.update(
              "UPDATE "
                  + table
                  + " SET state = 'IN_PROGRESS', attempts = attempts + 1, updated_at = ? "
                  + "WHERE id = ? AND state = 'PENDING'",
              now,
              id);
      if (rows == 1) {
        jdbc.one(
                "SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?" + jdbc.forUpdate(),
                rs -> map(rs, kind),
                id)
            .ifPresent(out::add);
      }
    }
  }

  @Override
  public Optional<Item> findByTx(UUID txId, Kind kind) {
    return jdbc.one(
        "SELECT " + COLUMNS + " FROM " + table(kind) + " WHERE tx_id = ?",
        rs -> map(rs, kind),
        txId);
  }

  @Override
  public boolean markCompleted(UUID id, long now) {
    return transitionFromInProgress(id, "COMPLETED", now, false, null);
  }

  @Override
  public boolean release(UUID id, long now) {
    // attempts wurde bereits beim Beanspruchen erhoeht.
    return transitionFromInProgress(id, "PENDING", now, false, null);
  }

  @Override
  public boolean markReview(UUID id, String reason, long now) {
    String r =
        reason != null && reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
    return transitionFromInProgress(id, "REVIEW", now, true, r);
  }

  private boolean transitionFromInProgress(
      UUID id, String target, long now, boolean withReason, String reason) {
    for (Kind kind : KINDS) {
      int rows =
          withReason
              ? jdbc.update(
                  "UPDATE "
                      + table(kind)
                      + " SET state = ?, review_reason = ?, updated_at = ? "
                      + "WHERE id = ? AND state = 'IN_PROGRESS'",
                  target,
                  reason,
                  now,
                  id)
              : jdbc.update(
                  "UPDATE "
                      + table(kind)
                      + " SET state = ?, updated_at = ? WHERE id = ? AND state = 'IN_PROGRESS'",
                  target,
                  now,
                  id);
      if (rows == 1) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<Item> findByState(State state, int limit) {
    List<Item> out = new ArrayList<>();
    for (Kind kind : KINDS) {
      int missing = limit - out.size();
      if (missing <= 0) {
        break;
      }
      out.addAll(
          jdbc.list(
              "SELECT "
                  + COLUMNS
                  + " FROM "
                  + table(kind)
                  + " WHERE state = ? ORDER BY created_at ASC, id ASC LIMIT ?",
              rs -> map(rs, kind),
              state,
              missing));
    }
    return out;
  }

  private static Item map(ResultSet rs, Kind kind) throws SQLException {
    return new Item(
        Jdbc.uuid(rs, "id"),
        Jdbc.uuid(rs, "tx_id"),
        Jdbc.uuid(rs, "player_uuid"),
        kind,
        rs.getLong("amount"),
        State.valueOf(rs.getString("state")),
        rs.getInt("attempts"),
        rs.getLong("updated_at"));
  }
}
