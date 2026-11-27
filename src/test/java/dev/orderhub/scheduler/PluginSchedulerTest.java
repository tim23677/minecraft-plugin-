package dev.orderhub.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** MockBukkit simuliert Paper, nicht Folia: hier wird nur der Bukkit-Zweig geprueft. */
class PluginSchedulerTest {

  private ServerMock server;
  private OrderHubPlugin plugin;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.load(OrderHubPlugin.class);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void selectsBukkitImplementationOutsideFolia() {
    assertThat(plugin.scheduler()).isInstanceOf(BukkitPluginScheduler.class);
  }

  @Test
  void globalTaskRunsOnNextTick() {
    var counter = new AtomicInteger();
    plugin.scheduler().runGlobal(counter::incrementAndGet);
    server.getScheduler().performOneTick();
    assertThat(counter).hasValue(1);
  }

  private BukkitPluginScheduler bukkit() {
    return (BukkitPluginScheduler) plugin.scheduler();
  }

  @Test
  void runLaterWaitsForTheDelay() {
    var counter = new AtomicInteger();
    plugin.scheduler().runLater(counter::incrementAndGet, 3);
    server.getScheduler().performTicks(2);
    assertThat(counter).hasValue(0);
    server.getScheduler().performOneTick();
    assertThat(counter).hasValue(1);
    server.getScheduler().performTicks(5);
    assertThat(counter).hasValue(1);
  }

  @Test
  void runTimerRepeatsUntilCancelAll() {
    var counter = new AtomicInteger();
    plugin.scheduler().runTimer(counter::incrementAndGet, 1, 2);
    server.getScheduler().performTicks(5);
    int before = counter.get();
    assertThat(before).isGreaterThanOrEqualTo(2);
    plugin.scheduler().cancelAll();
    server.getScheduler().performTicks(10);
    assertThat(counter).hasValue(before);
  }

  @Test
  void cancelAllDropsPendingTasks() {
    var counter = new AtomicInteger();
    plugin.scheduler().runGlobal(counter::incrementAndGet);
    plugin.scheduler().runLater(counter::incrementAndGet, 2);
    plugin.scheduler().cancelAll();
    server.getScheduler().performTicks(5);
    assertThat(counter).hasValue(0);
    assertThat(bukkit().trackedTasks()).isZero();
  }

  @Test
  void finishedHandlesAreDropped() {
    plugin.scheduler().runGlobal(() -> {});
    assertThat(bukkit().trackedTasks()).isEqualTo(1);
    server.getScheduler().performOneTick();
    assertThat(bukkit().trackedTasks()).isZero();
  }

  @Test
  void runOnReportsOnlinePlayersAsAccepted() {
    PlayerMock player = server.addPlayer();
    var counter = new AtomicInteger();
    assertThat(plugin.scheduler().runOn(player, counter::incrementAndGet)).isTrue();
    server.getScheduler().performOneTick();
    assertThat(counter).hasValue(1);
  }

  @Test
  void runOnRefusesAPlayerWhoLeft() {
    PlayerMock player = server.addPlayer();
    player.disconnect();
    var counter = new AtomicInteger();
    assertThat(plugin.scheduler().runOn(player, counter::incrementAndGet)).isFalse();
    assertThat(plugin.scheduler().tryRunOn(player, counter::incrementAndGet)).isFalse();
    server.getScheduler().performTicks(2);
    assertThat(counter).hasValue(0);
  }

  @Test
  void supplyAsyncDeliversTheValue() throws Exception {
    CompletableFuture<String> future = plugin.scheduler().supplyAsync(() -> "ok");
    server.getScheduler().waitAsyncTasksFinished();
    assertThat(future.get()).isEqualTo("ok");
  }

  @Test
  void supplyAsyncCarriesTheException() {
    CompletableFuture<String> future =
        plugin
            .scheduler()
            .supplyAsync(() -> {
              throw new IllegalStateException("boom");
            });
    server.getScheduler().waitAsyncTasksFinished();
    assertThat(future).isCompletedExceptionally();
    org.assertj.core.api.Assertions.assertThatThrownBy(future::get)
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseMessage("boom");
  }
}
