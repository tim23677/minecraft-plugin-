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
      server.getScheduler().waitAsyncTasksFinished();
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mine",
        "market",
        "market newest",
        "market price-asc",
        "market diamond sword",
        "claims",
        "stats",
        "history",
        "favorites"
      })
  void withThePermissionASubcommandOpensAMenuAndNeverRefuses(String command) {
    PlayerMock player =
        player("orderhub.create", "orderhub.market", "orderhub.stats", "orderhub.favorites");
    player.performCommand("order " + command);
    Optional<Menu> menu = awaitMenu(player, 300);
    assertThat(menu).as(command).isPresent();
    assertThat(menu.get()).as(command).isNotInstanceOf(MainMenu.class);
    assertThat(player.nextMessage()).as(command).isNull();
  }

  @Test
  void aSortNameAndASearchTextOpenDifferentMarketViews() {
    PlayerMock bySort = player("orderhub.market");
    bySort.performCommand("order market price-asc");
    Menu sorted = awaitMenu(bySort, 300).orElseThrow();
    PlayerMock byText = player("orderhub.market");
    byText.performCommand("order market price-asc diamond");
    Menu searched = awaitMenu(byText, 300).orElseThrow();
    assertThat(sorted).isNotNull();
    assertThat(searched).isNotNull();
    assertThat(bySort.nextMessage()).isNull();
    assertThat(byText.nextMessage()).isNull();
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
  void aSubcommandIsCaseInsensitive() {
    PlayerMock player = player("orderhub.market");
    player.performCommand("order MARKET");
    assertThat(awaitMenu(player, 300)).isPresent();
  }

  @Test
  void permissionToOneAreaDoesNotUnlockAnother() {
    PlayerMock player = player("orderhub.market");
    player.performCommand("order stats");
    assertThat(player.nextMessage()).contains("do not have permission");
    player.performCommand("order mine");
    assertThat(player.nextMessage()).contains("do not have permission");
    player.performCommand("order template list");
    assertThat(player.nextMessage()).contains("do not have permission");
    player.performCommand("order sell");
    assertThat(player.nextMessage()).doesNotContain("do not have permission");
    assertThat(awaitMenu(player, 20)).isEmpty();
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
        .containsExactly("1", "16", "64", "576", "2304");
    assertThat(suggestions(player, "order create 6")).containsExactly("64", "576");
    assertThat(suggestions(player, "order create 64 ")).isEmpty();
    assertThat(suggestions(player, "order mine ")).isEmpty();
    assertThat(suggestions(player, "order market newest ")).isEmpty();
  }
}
