package dev.orderhub.discord;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscordWorkerTest {

  @TempDir Path dir;
  private JdbcStorage storage;
  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(204);
  private final AtomicInteger hits = new AtomicInteger();
  private DiscordWorker worker;

  @BeforeEach
  void setUp() throws Exception {
    storage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("t.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          hits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (status.get() == 429) {
            exchange.getResponseHeaders().add("Retry-After", "30");
          }
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    worker =
        new DiscordWorker(
            storage,
            new WebhookSender(),
            new DiscordWorker.Config(() -> Optional.of(target), 3, 5, 300),
            Logger.getLogger("test"));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    storage.close();
  }

  private void enqueue(String payload) {
    storage
        .transact(u -> {
          u.outbox().enqueueDiscord(payload, 0);
          return null;
        })
        .join();
  }

  private int due(long at) {
    return storage.transact(u -> u.outbox().dueDiscord(at, 50)).join().size();
  }

  @Test
  void sendsAndMarksSent() {
    enqueue("{\"a\":1}");
    assertThat(worker.runOnce(1000)).isEqualTo(1);
    assertThat(due(10_000_000)).isZero();
    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void rateLimitPausesTheWholeWorker() {
    status.set(429);
    enqueue("{\"a\":1}");
    enqueue("{\"a\":2}");
    assertThat(worker.runOnce(1000)).isZero();
    assertThat(hits.get()).isEqualTo(1);
    // innerhalb der Pause wird gar nicht gesendet
    assertThat(worker.runOnce(5000)).isZero();
    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void transientErrorBacksOffAndEventuallyFails() {
    status.set(503);
    enqueue("{\"a\":1}");
    worker.runOnce(1000);
    assertThat(due(1000)).isZero();
    assertThat(due(1000 + 5000)).isEqualTo(1);
    worker.runOnce(10_000);
    worker.runOnce(100_000);
    assertThat(due(10_000_000)).isZero(); // nach 3 Versuchen FAILED, nicht mehr faellig
    assertThat(hits.get()).isEqualTo(3);
  }

  @Test
  void permanentRejectionIsNotRetried() {
    status.set(404);
    enqueue("{\"a\":1}");
    worker.runOnce(1000);
    assertThat(due(10_000_000)).isZero();
    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void noWebhookMeansNothingHappens() {
    DiscordWorker idle =
        new DiscordWorker(
            storage,
            new WebhookSender(),
            new DiscordWorker.Config(Optional::empty, 3, 5, 300),
            Logger.getLogger("test"));
    enqueue("{\"a\":1}");
    assertThat(idle.runOnce(1000)).isZero();
    assertThat(due(1000)).isEqualTo(1);
  }

  @Test
  void serviceDoesNotWriteWhenDisabled() {
    DiscordService off =
        new DiscordService(storage, DiscordService.Config::disabled, Logger.getLogger("test"));
    off.publish(DiscordService.Event.LARGE_ORDER, "t", "b");
    assertThat(due(10_000_000)).isZero();
    DiscordService on =
        new DiscordService(
            storage,
            () ->
                new DiscordService.Config(
                    true, "OrderHub", java.util.Set.of(DiscordService.Event.LARGE_ORDER), 1),
            Logger.getLogger("test"));
    on.publish(DiscordService.Event.LARGE_ORDER, "t", "b");
    on.publish(DiscordService.Event.ORDER_CANCELLED, "ignored", "event not enabled");
    var items = storage.transact(u -> u.outbox().dueDiscord(Long.MAX_VALUE, 50)).join();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).payload()).contains("\"allowed_mentions\"");
  }
}
