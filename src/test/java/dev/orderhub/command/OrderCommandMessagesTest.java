package dev.orderhub.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import dev.orderhub.testsupport.PluginReady;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Prueft, was die Befehle antworten, nicht nur dass sie laufen. */
class OrderCommandMessagesTest {

  private ServerMock server;
  private OrderHubPlugin plugin;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.load(OrderHubPlugin.class);
    assertThat(PluginReady.await(server, plugin)).isTrue();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private PlayerMock player(String... permissions) {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", true);
    for (String permission : permissions) {
      player.addAttachment(plugin, permission, true);
    }
    return player;
  }

  @Test
  void consoleGetsTheRefusalMessage() {
    var console = server.getConsoleSender();
    server.dispatchCommand(console, "order");
    assertThat(console.nextMessage()).contains("only be used by players");
    assertThat(console.nextMessage()).isNull();
  }

  @Test
  void helpListsTheSubcommands() {
    PlayerMock player = player();
    player.performCommand("order help");
    assertThat(player.nextMessage()).contains("/order create").contains("deliver <id>");
  }

  @Test
  void unknownSubcommandSaysSo() {
    PlayerMock player = player();
    player.performCommand("order nonsense");
    assertThat(player.nextMessage()).contains("Unknown subcommand");
  }

  @Test
  void createWithTheWrongNumberOfArgumentsShowsTheUsage() {
    PlayerMock player = player("orderhub.create");
    player.performCommand("order create 10");
    assertThat(player.nextMessage()).contains("Hold the item");
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancel", "deliver"})
  void anOrderIdIsRequired(String sub) {
    PlayerMock player = player("orderhub.create", "orderhub.market");
    player.performCommand("order " + sub);
    assertThat(player.nextMessage()).contains("not a valid number");
    player.performCommand("order " + sub + " abc");
    assertThat(player.nextMessage()).contains("'abc' is not a valid number");
    player.performCommand("order " + sub + " 1; DROP TABLE orders");
    assertThat(player.nextMessage()).contains("not a valid number");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "create",
        "mine",
        "market",
        "deliver 1",
        "cancel 1",
        "stats",
        "history",
        "favorites",
        "analytics"
      })
  void everySubcommandNeedsItsPermission(String command) {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", true);
    for (String perm :
        new String[] {
          "orderhub.create", "orderhub.market", "orderhub.stats", "orderhub.favorites"
        }) {
      player.addAttachment(plugin, perm, false);
    }
    player.performCommand("order " + command);
    assertThat(player.nextMessage()).contains("do not have permission");
    assertThat(plugin.menus().current(player.getUniqueId())).isEmpty();
  }

  @Test
  void aPlayerWithoutTheBasePermissionGetsNoCommandAtAll() {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", false);
    player.performCommand("order");
    server.getScheduler().performOneTick();
    assertThat(plugin.menus().current(player.getUniqueId())).isEmpty();
  }

  @Test
  void adminCommandIsInvisibleWithoutPermission() {
    PlayerMock player = server.addPlayer();
    player.performCommand("orderadmin statistics");
    assertThat(player.nextMessage()).contains("Unknown command");
    assertThat(player.hasPermission("orderhub.admin")).isFalse();
  }

  @Test
  void adminCancelAsksForConfirmationThenForAReason() {
    PlayerMock admin = server.addPlayer();
    admin.addAttachment(plugin, "orderhub.admin", true);
    admin.performCommand("orderadmin cancel 1");
    assertThat(admin.nextMessage()).contains("Repeat the command");
    admin.performCommand("orderadmin cancel 1 confirm");
    assertThat(admin.nextMessage()).contains("reason is required");
    admin.performCommand("orderadmin cancel abc");
    assertThat(admin.nextMessage()).contains("'abc' is not a valid number");
  }

  @Test
  void adminHelpListsTheSubcommands() {
    PlayerMock admin = server.addPlayer();
    admin.addAttachment(plugin, "orderhub.admin", true);
    admin.performCommand("orderadmin");
    String first = admin.nextMessage();
    String second = admin.nextMessage();
    assertThat(first + " " + second).contains("cancel <id> confirm <reason>");
  }
}
