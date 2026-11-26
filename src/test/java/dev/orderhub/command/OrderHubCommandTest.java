package dev.orderhub.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import dev.orderhub.gui.OrderHubMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OrderHubCommandTest {

  private ServerMock server;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    MockBukkit.load(OrderHubPlugin.class);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void playerOpensMenu() {
    PlayerMock player = server.addPlayer();
    player.performCommand("orderhub");
    assertThat(player.getOpenInventory().getTopInventory().getHolder())
        .isInstanceOf(OrderHubMenu.class);
  }

  @Test
  void consoleGetsRefusal() {
    var console = server.getConsoleSender();
    server.dispatchCommand(console, "orderhub");
    assertThat(server.getOnlinePlayers()).isEmpty();
  }
}
