package dev.orderhub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class OrderHubPluginTest {

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
  void pluginEnablesAndRegistersCommand() {
    assertThat(plugin.isEnabled()).isTrue();
    assertThat(plugin.getCommand("orderhub")).isNotNull();
    assertThat(plugin.scheduler()).isNotNull();
  }

  @Test
  void pluginDisablesCleanly() {
    server.getPluginManager().disablePlugin(plugin);
    assertThat(plugin.isEnabled()).isFalse();
  }
}
