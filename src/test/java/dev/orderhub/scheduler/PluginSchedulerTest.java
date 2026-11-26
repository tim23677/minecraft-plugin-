package dev.orderhub.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
}
