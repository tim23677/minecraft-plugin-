package dev.orderhub.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import dev.orderhub.gui.MainMenu;
import dev.orderhub.gui.Menu;
import dev.orderhub.testsupport.PluginReady;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mit Berechtigung fuehrt jeder Unterbefehl wirklich etwas aus, ohne sie nichts. Die bestehenden
 * Tests pruefen nur die Ablehnung. Hier geht es um den Gegenfall: ein Spieler mit der richtigen
 * Berechtigung bekommt sein Menue, und die Vorschlaege folgen der Berechtigung.
 */
class OrderCommandRoutingTest {

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

  /** Treibt Async- und Tick-Aufgaben, bis ein Menue offen ist, hoechstens {@code rounds} Runden. */
  private Optional<Menu> awaitMenu(PlayerMock player, int rounds) {
    for (int i = 0; i < rounds; i++) {
      // No waitAsyncTasksFinished(): the plugin has repeating async tasks, so it never returns.
      server.getScheduler().performOneTick();
      Optional<Menu> current = plugin.menus().current(player.getUniqueId());
      if (current.isPresent()) {
        return current;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return Optional.empty();
  }

  private static final String[] ALL_PERMISSIONS = {
    "orderhub.create", "orderhub.market", "orderhub.stats", "orderhub.favorites"
  };

  @Test
  void mineOpensThePagedListOfTheOwnOrdersWithoutAnyMessage() {
    PlayerMock player = player(ALL_PERMISSIONS);
    player.performCommand("order mine");
    assertThat(awaitMenu(player, 300))
        .hasValueSatisfying(m -> assertThat(m).isNotInstanceOf(MainMenu.class));
    assertThat(player.nextMessage()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "market",
        "MARKET",
        "market newest",
        "market price-asc",
        "market price_asc",
        "market diamond sword"
      })
  void marketOpensTheMarketMenuForASortNameAndForSearchText(String command) {
    PlayerMock player = player(ALL_PERMISSIONS);
    player.performCommand("order " + command);
    assertThat(awaitMenu(player, 300))
        .as(command)
        .hasValueSatisfying(m -> assertThat(m).isNotInstanceOf(MainMenu.class));
    // leerer Markt: der Spieler bekommt den Hinweis, nie eine Ablehnung
    String message = player.nextMessage();
    assertThat(message).as(command).contains("No open orders");
    assertThat(message).doesNotContain("permission");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "claims|nothing to collect",
        "returns|nothing to collect",
        "stats|No trades yet",
        "history|No deliveries yet",
        "favorites|No favorites",
        "analytics|most open orders",
        "sell|Hold the item",
        "template list|no templates",
        "deliver 1|#1 not found",
        "cancel 1|#1 not found"
      })
  void withThePermissionEverySubcommandAnswersWithItsOwnContentNeverWithARefusal(
      String command, String expected) {
    PlayerMock player = player(ALL_PERMISSIONS);
    player.performCommand("order " + command);
    // waitAsyncTasksFinished() never returns while the plugin has repeating async tasks, so the
    // test ticks the scheduler until the answer arrives.
    String message = null;
    for (int i = 0; i < 1000 && message == null; i++) {
      server.getScheduler().performOneTick();
      message = player.nextMessage();
      if (message == null) {
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    assertThat(message).as(command).contains(expected);
    assertThat(message).doesNotContain("permission");
  }

  @Test
  void theBaseCommandWithoutArgumentsOpensTheMainMenuAndHelpOpensNone() {
    PlayerMock player = player();
    player.performCommand("order help");
    assertThat(awaitMenu(player, 20)).isEmpty();
    assertThat(player.nextMessage()).isNotNull();
    player.performCommand("order");
    assertThat(awaitMenu(player, 300))
        .hasValueSatisfying(m -> assertThat(m).isInstanceOf(MainMenu.class));
  }

  @Test
  void permissionToOneAreaDoesNotUnlockAnother() {
    PlayerMock player = player("orderhub.market");
    for (String denied : new String[] {"orderhub.create", "orderhub.stats", "orderhub.favorites"}) {
      player.addAttachment(plugin, denied, false);
    }
    for (String command : new String[] {"stats", "history", "favorites", "mine", "template list"}) {
      player.performCommand("order " + command);
      assertThat(player.nextMessage()).as(command).contains("do not have permission");
    }
    player.performCommand("order sell");
    assertThat(player.nextMessage()).doesNotContain("permission");
  }

  // ---- Vorschlaege ----

  private List<String> suggestions(PlayerMock player, String input) {
    var dispatcher =
        new com.mojang.brigadier.CommandDispatcher<
            io.papermc.paper.command.brigadier.CommandSourceStack>();
    dispatcher.getRoot().addChild(plugin.orderCommandNode());
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
  void marketSuggestsSortNamesInKebabCaseAndFiltersByPrefix() {
    PlayerMock player = player();
    List<String> all = suggestions(player, "order market ");
    assertThat(all).isNotEmpty().contains("newest").allMatch(s -> s.equals(s.toLowerCase()));
    assertThat(all).noneMatch(s -> s.contains("_"));
    List<String> filtered = suggestions(player, "order market N");
    assertThat(filtered).contains("newest").allMatch(s -> s.startsWith("n"));
    assertThat(filtered.size()).isLessThan(all.size());
  }

  @Test
  void createSuggestsStackSizesAndOnlyForTheSecondWord() {
    PlayerMock player = player();
    assertThat(suggestions(player, "order create "))
        .containsExactlyInAnyOrder("1", "16", "64", "576", "2304");
    assertThat(suggestions(player, "order create 6")).containsExactly("64");
    assertThat(suggestions(player, "order create 64 ")).isEmpty();
    assertThat(suggestions(player, "order mine ")).isEmpty();
    assertThat(suggestions(player, "order market newest ")).isEmpty();
  }
}
