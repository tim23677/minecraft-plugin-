package dev.orderhub.discord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Sendet eine Nachricht an einen Webhook. Blockiert und gehoert deshalb nie auf einen
 * Minecraft-Thread, sondern auf den Async-Scheduler.
 */
public final class WebhookSender {

  /** Ergebnis eines Sendeversuchs. */
  public sealed interface Result {
    record Sent() implements Result {}

    /** Rate Limit: nach {@code retryAfterSeconds} erneut versuchen. */
    record RateLimited(long retryAfterSeconds) implements Result {}

    /** Voruebergehender Fehler (5xx, Netzwerk): mit Backoff wiederholen. */
    record Retry(String reason) implements Result {}

    /** Dauerhafter Fehler (4xx ausser 429): nicht wiederholen. */
    record Rejected(int status) implements Result {}
  }

  private final HttpClient client;

  public WebhookSender() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  WebhookSender(HttpClient client) {
    this.client = client;
  }

  /**
   * Das Ziel muss vorher ueber {@link WebhookUrl#validate} gelaufen sein (Tests duerfen davon
   * abweichen).
   */
  public Result send(URI target, String username, DiscordMessage message) {
    return sendRaw(target, message.toJson(username));
  }

  /** Sendet einen fertigen JSON-Body (aus der Outbox). */
  public Result sendRaw(URI target, String json) {
    HttpRequest request =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Result.Sent();
      }
      if (status == 429) {
        return new Result.RateLimited(retryAfter(response));
      }
      if (status >= 500) {
        return new Result.Retry("http " + status);
      }
      return new Result.Rejected(status);
    } catch (IOException e) {
      return new Result.Retry(e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result.Retry("interrupted");
    }
  }

  private static long retryAfter(HttpResponse<String> response) {
    Optional<String> header = response.headers().firstValue("Retry-After");
    if (header.isPresent()) {
      try {
        return Math.max(1, Math.min(3600, (long) Math.ceil(Double.parseDouble(header.get()))));
      } catch (NumberFormatException ignored) {
        // Fallback unten
      }
    }
    return 5;
  }
}
