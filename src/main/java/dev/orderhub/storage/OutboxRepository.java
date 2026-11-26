package dev.orderhub.storage;

import java.util.List;

/** Ausgehende Nachrichten (Discord) und Aenderungsereignisse fuer andere Server (Cache). */
public interface OutboxRepository {

  record DiscordItem(long id, String payload, int attempts) {}

  record CacheEvent(long id, String channel, String payload, String originServer, long createdAt) {}

  void enqueueDiscord(String payload, long now);

  /** PENDING-Eintraege mit faelligem Versuch, aelteste zuerst. */
  List<DiscordItem> dueDiscord(long now, int limit);

  /**
   * Beansprucht eine PENDING-Nachricht bedingt per UPDATE. Nur ein Aufrufer gewinnt, solange der
   * Claim gilt (oder bis er ablaeuft).
   *
   * @return {@code true}, wenn dieser Aufrufer die Nachricht senden darf
   */
  boolean claimDiscord(long id, String claimant, long now, long claimedUntil);

  void discordSent(long id, long now);

  /** Naechster Versuch (Backoff) nach einem Fehler. */
  void discordRetry(long id, int attempts, long nextAttemptAt, String error, long now);

  void discordFailed(long id, int attempts, String error, long now);

  void publishCacheEvent(String channel, String payload, String originServer, long now);

  /** Ereignisse mit {@code id > afterId} von anderen Servern, aufsteigend. */
  List<CacheEvent> cacheEventsAfter(long afterId, String exceptServer, int limit);

  long latestCacheEventId();

  int purgeCacheEventsBefore(long cutoff);
}
