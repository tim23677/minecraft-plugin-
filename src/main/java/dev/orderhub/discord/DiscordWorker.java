package dev.orderhub.discord;

import dev.orderhub.storage.OutboxRepository;
import dev.orderhub.storage.Storage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Arbeitet die Discord-Outbox ab: Rate Limits werden beachtet, vorubergehende Fehler mit Backoff
 * wiederholt, dauerhafte Fehler und erschoepfte Versuche als FAILED markiert. Laeuft nur auf dem
 * Async-Scheduler.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Logger und Storage sind gemeinsam genutzte Infrastruktur")
public final class DiscordWorker {

  /** Laufzeitkonfiguration des Versands. */
  public record Config(
      Supplier<Optional<URI>> webhook,
      int maxAttempts,
      long initialBackoffSeconds,
      long maxBackoffSeconds) {}

  private final Storage storage;
  private final WebhookSender sender;
  private final Config config;
  private final Logger logger;
  private static final long CLAIM_MILLIS = 60_000L;

  private final String instanceId = java.util.UUID.randomUUID().toString();
  private volatile long pausedUntilMillis;

  public DiscordWorker(Storage storage, WebhookSender sender, Config config, Logger logger) {
    this.storage = storage;
    this.sender = sender;
    this.config = config;
    this.logger = logger;
  }

  /** Ein Durchlauf. Liefert die Zahl erfolgreich gesendeter Nachrichten. */
  public synchronized int runOnce(long nowMillis) {
    if (nowMillis < pausedUntilMillis) {
      return 0;
    }
    Optional<URI> target = config.webhook().get();
    if (target.isEmpty()) {
      return 0;
    }
    String claimant = storage.serverId() + ":" + instanceId;
    List<OutboxRepository.DiscordItem> due =
        storage.transact(uow -> uow.outbox().dueDiscord(nowMillis, 10)).join();
    int sent = 0;
    for (OutboxRepository.DiscordItem item : due) {
      boolean mine =
          storage
              .transact(uow ->
                  uow.outbox()
                      .claimDiscord(item.id(), claimant, nowMillis, nowMillis + CLAIM_MILLIS))
              .join();
      if (!mine) {
        continue; // ein anderer Server sendet diese Nachricht
      }
      WebhookSender.Result result = sender.sendRaw(target.get(), item.payload());
      if (result instanceof WebhookSender.Result.Sent) {
        storage
            .transact(uow -> {
              uow.outbox().discordSent(item.id(), nowMillis);
              return null;
            })
            .join();
        sent++;
      } else if (result instanceof WebhookSender.Result.RateLimited limited) {
        pausedUntilMillis = nowMillis + limited.retryAfterSeconds() * 1000L;
        reschedule(item, nowMillis, limited.retryAfterSeconds(), "rate limited");
        return sent;
      } else if (result instanceof WebhookSender.Result.Retry retry) {
        long wait =
            Backoff.seconds(
                item.attempts() + 1, config.initialBackoffSeconds(), config.maxBackoffSeconds());
        reschedule(item, nowMillis, wait, retry.reason());
      } else if (result instanceof WebhookSender.Result.Rejected rejected) {
        logger.warning("discord rejected message " + item.id() + " with HTTP " + rejected.status());
        storage
            .transact(uow -> {
              uow.outbox()
                  .discordFailed(
                      item.id(), item.attempts() + 1, "http " + rejected.status(), nowMillis);
              return null;
            })
            .join();
      }
    }
    return sent;
  }

  private void reschedule(
      OutboxRepository.DiscordItem item, long now, long waitSeconds, String reason) {
    int attempts = item.attempts() + 1;
    storage
        .transact(uow -> {
          if (attempts >= config.maxAttempts()) {
            uow.outbox().discordFailed(item.id(), attempts, reason, now);
          } else {
            uow.outbox().discordRetry(item.id(), attempts, now + waitSeconds * 1000L, reason, now);
          }
          return null;
        })
        .join();
  }
}
