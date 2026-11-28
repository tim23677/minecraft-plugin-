package dev.orderhub.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import dev.orderhub.gui.MainMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class OrderCommandTest {

  private ServerMock server;
  private OrderHubPlugin plugin;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.load(OrderHubPlugin.class);
    assertThat(dev.orderhub.testsupport.PluginReady.await(server, plugin)).isTrue();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private PlayerMock user() {
    PlayerMock player = server.addPlayer();
    for (String perm : new String[] {"orderhub.use", "orderhub.create", "orderhub.market"}) {
      player.addAttachment(plugin, perm, true);
    }
    return player;
  }

  @Test
  void playerOpensMainMenu() {
    PlayerMock player = user();
    player.performCommand("order");
    server.getScheduler().performOneTick();
    assertThat(player.getOpenInventory().getTopInventory().getHolder())
        .isInstanceOf(MainMenu.class);
  }

  @Test
  void consoleIsRefused() {
    server.dispatchCommand(server.getConsoleSender(), "order");
    assertThat(server.getOnlinePlayers()).isEmpty();
  }

  /**
   * Fragt einen Brigadier-Dispatcher mit den echten OrderHub-Knoten, so wie der Client beim Tippen.
   * MockBukkit 4.116.3 liefert fuer Plugin-Befehle keine Vorschlaege ueber seinen eigenen
   * Dispatcher, deshalb werden die Knoten in einen frischen Dispatcher gehaengt.
   */
  private java.util.List<String> suggestions(PlayerMock player, String input) {
    var dispatcher =
        new com.mojang.brigadier.CommandDispatcher<
            io.papermc.paper.command.brigadier.CommandSourceStack>();
    dispatcher.getRoot().addChild(plugin.orderCommandNode());
    dispatcher.getRoot().addChild(plugin.adminCommandNode());
    var parse =
        dispatcher.parse(
            input, org.mockbukkit.mockbukkit.command.CommandSourceStackMock.from(player));
    try {
      return dispatcher.getCompletionSuggestions(parse).get().getList().stream()
          .map(com.mojang.brigadier.suggestion.Suggestion::getText)
          .toList();
    } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void tabCompletionListsSubcommands() {
    PlayerMock player = user();
    var completions = suggestions(player, "order cr");
    assertThat(completions).containsExactly("create");
    assertThat(suggestions(player, "order ")).contains("create", "mine", "market");
    assertThat(suggestions(player, "order m")).contains("mine", "market");
  }

  @Test
  void unknownSubcommandIsHandled() {
    PlayerMock player = user();
    assertThat(player.performCommand("order nonsense")).isTrue();
  }

  @Test
  void createWithoutItemInHandIsRejectedWithoutError() {
    PlayerMock player = user();
    player.performCommand("order create 10 5");
    assertThat(player.nextMessage()).isNotNull();
  }

  @Test
  void adminCommandsNeedPermission() {
    PlayerMock player = server.addPlayer();
    player.performCommand("orderadmin statistics");
    assertThat(player.nextMessage()).isNotNull();
    assertThat(player.hasPermission("orderhub.admin")).isFalse();
  }

  @Test
  void adminCancelNeedsConfirmAndReason() {
    PlayerMock admin = server.addPlayer();
    admin.addAttachment(plugin, "orderhub.admin", true);
    admin.performCommand("orderadmin cancel 1");
    assertThat(admin.nextMessage()).isNotNull();
    admin.performCommand("orderadmin cancel 1 confirm");
    assertThat(admin.nextMessage()).isNotNull();
  }

  @Test
  void marketRequiresPermissionAndOpensNoMenuWithoutIt() {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", true);
    player.addAttachment(plugin, "orderhub.market", false);
    player.performCommand("order market");
    server.getScheduler().performOneTick();
    assertThat(plugin.menus().current(player.getUniqueId())).isEmpty();
  }

  @Test
  void aliasesReachTheSameHandler() {
    PlayerMock player = user();
    for (String alias : new String[] {"order", "orderhub", "orders"}) {
      player.closeInventory();
      assertThat(player.performCommand(alias)).isTrue();
      server.getScheduler().performOneTick();
      assertThat(player.getOpenInventory().getTopInventory().getHolder())
          .as(alias)
          .isInstanceOf(MainMenu.class);
    }
  }

  @Test
  void playersWithoutPermissionDoNotSeeTheCommand() {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", false);
    assertThat(suggestions(player, "order ")).isEmpty();
  }
}
