package dev.orderhub.discord;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiscordTest {

  @Test
  void onlyDiscordHttpsWebhooksAreAccepted() {
    assertThat(WebhookUrl.validate("https://discord.com/api/webhooks/1/abc")).isPresent();
    assertThat(WebhookUrl.validate("https://discordapp.com/api/webhooks/1/abc")).isPresent();
    assertThat(WebhookUrl.validate("http://discord.com/api/webhooks/1/abc")).isEmpty();
    assertThat(WebhookUrl.validate("https://evil.example/api/webhooks/1/abc")).isEmpty();
    assertThat(WebhookUrl.validate("https://discord.com.evil.example/api/webhooks/1/a")).isEmpty();
    assertThat(WebhookUrl.validate("https://user:pw@discord.com/api/webhooks/1/abc")).isEmpty();
    assertThat(WebhookUrl.validate("https://discord.com:8443/api/webhooks/1/abc")).isEmpty();
    assertThat(WebhookUrl.validate("https://discord.com/other")).isEmpty();
    assertThat(WebhookUrl.validate("https://127.0.0.1/api/webhooks/1/abc")).isEmpty();
    assertThat(WebhookUrl.validate("")).isEmpty();
    assertThat(WebhookUrl.validate(null)).isEmpty();
  }

  @Test
  void redactHidesToken() {
    assertThat(WebhookUrl.redact("https://discord.com/api/webhooks/1/SECRET"))
        .doesNotContain("SECRET")
        .doesNotContain("1/");
  }

  @Test
  void jsonEscapesAndDisablesMentions() {
    String json = new DiscordMessage("T\"x", "@everyone line1\nline2", 1).toJson("Order\\Hub");
    assertThat(json).contains("\"allowed_mentions\":{\"parse\":[]}");
    assertThat(json).contains("T\\\"x").contains("line1\\nline2").contains("Order\\\\Hub");
    assertThat(json).doesNotContain("@everyone");
  }

  @Test
  void backoffGrowsAndIsCapped() {
    assertThat(Backoff.seconds(1, 5, 300)).isEqualTo(5);
    assertThat(Backoff.seconds(2, 5, 300)).isEqualTo(10);
    assertThat(Backoff.seconds(3, 5, 300)).isEqualTo(20);
    assertThat(Backoff.seconds(20, 5, 300)).isEqualTo(300);
    assertThat(Backoff.seconds(0, 5, 300)).isZero();
  }

  private static HttpServer server(int[] statuses, AtomicInteger hits, AtomicReference<String> body)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          int n = hits.getAndIncrement();
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int status = statuses[Math.min(n, statuses.length - 1)];
          if (status == 429) {
            exchange.getResponseHeaders().add("Retry-After", "2.5");
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  private WebhookSender.Result sendTo(HttpServer s) {
    URI uri = URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/hook");
    return new WebhookSender().send(uri, "OrderHub", new DiscordMessage("t", "b", 1));
  }

  @Test
  void statusCodesMapToResults() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer ok = server(new int[] {204}, hits, body);
    try {
      assertThat(sendTo(ok)).isInstanceOf(WebhookSender.Result.Sent.class);
      assertThat(body.get()).contains("\"embeds\"");
    } finally {
      ok.stop(0);
    }
    HttpServer limited = server(new int[] {429}, new AtomicInteger(), body);
    try {
      assertThat(sendTo(limited)).isEqualTo(new WebhookSender.Result.RateLimited(3));
    } finally {
      limited.stop(0);
    }
    HttpServer down = server(new int[] {503}, new AtomicInteger(), body);
    try {
      assertThat(sendTo(down)).isInstanceOf(WebhookSender.Result.Retry.class);
    } finally {
      down.stop(0);
    }
    HttpServer gone = server(new int[] {404}, new AtomicInteger(), body);
    try {
      assertThat(sendTo(gone)).isEqualTo(new WebhookSender.Result.Rejected(404));
    } finally {
      gone.stop(0);
    }
  }

  @Test
  void connectionFailureIsRetryable() {
    URI dead = URI.create("http://127.0.0.1:1/hook");
    assertThat(new WebhookSender().send(dead, "OrderHub", new DiscordMessage("t", "b", 1)))
        .isInstanceOf(WebhookSender.Result.Retry.class);
  }
}
