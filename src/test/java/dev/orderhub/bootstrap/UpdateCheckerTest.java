package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{\"tag_name\":\"v1.1.0\"}");
  private final AtomicReference<String> userAgent = new AtomicReference<>();
  private URI url;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/release",
        exchange -> {
          userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/release");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aNewerTagIsReported() {
    assertThat(new UpdateChecker().newerVersion(url, "1.0.0")).contains("v1.1.0");
  }

  @Test
  void theSameOrAnOlderTagIsNotReported() {
    assertThat(new UpdateChecker().newerVersion(url, "1.1.0")).isEmpty();
    assertThat(new UpdateChecker().newerVersion(url, "2.0.0")).isEmpty();
  }

  @Test
  void errorsAndGarbageNeverThrow() {
    status.set(500);
    assertThat(new UpdateChecker().newerVersion(url, "1.0.0")).isEmpty();
    status.set(200);
    body.set("not json");
    assertThat(new UpdateChecker().newerVersion(url, "1.0.0")).isEmpty();
    assertThat(new UpdateChecker().newerVersion(URI.create("http://127.0.0.1:1/x"), "1.0.0"))
        .isEmpty();
  }

  @Test
  void versionsCompareNumerically() {
    assertThat(UpdateChecker.isNewer("1.2.0", "v1.10.0")).isTrue();
    assertThat(UpdateChecker.isNewer("1.0.0", "1.0.0")).isFalse();
    assertThat(UpdateChecker.isNewer("1.0.0-SNAPSHOT", "1.0.1")).isTrue();
    assertThat(UpdateChecker.isNewer("1.0", "1.0.1")).isTrue();
  }

  @Test
  void onlyOwnerSlashNameRepositoriesAreAccepted() {
    assertThat(UpdateChecker.validRepository("tim23677/minecraft-plugin-")).isTrue();
    assertThat(UpdateChecker.validRepository("a/b/c")).isFalse();
    assertThat(UpdateChecker.validRepository("../etc")).isFalse();
    assertThat(UpdateChecker.validRepository("")).isFalse();
    assertThatThrownBy(() -> UpdateChecker.latestReleaseUrl("evil.example/x?y"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(UpdateChecker.latestReleaseUrl("o/n").toString())
        .isEqualTo("https://api.github.com/repos/o/n/releases/latest");
  }

  @Test
  void theRequestCarriesOnlyAGenericUserAgent() {
    new UpdateChecker().newerVersion(url, "1.0.0");
    assertThat(userAgent.get()).isEqualTo("OrderHub-UpdateCheck");
  }
}
