package dev.orderhub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.OrderHubPlugin;
import dev.orderhub.api.OrderHubApi;
import dev.orderhub.api.OrderService.CreateOptions;
import dev.orderhub.api.OrderService.CreateRequest;
import dev.orderhub.api.OrderService.CreateStatus;
import dev.orderhub.config.Limits;
import dev.orderhub.testsupport.PluginReady;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** createOrder der API: Rechte, Limit, Gebuehren und Audit mit aufrufendem Plugin. */
class ApiCreateOrderTest {

  private ServerMock server;
  private OrderHubPlugin plugin;
  private OrderHubApi api;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.load(OrderHubPlugin.class);
    assertThat(PluginReady.await(server, plugin)).isTrue();
    api = server.getServicesManager().load(OrderHubApi.class);
    assertThat(api).isNotNull();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private CreateRequest request(UUID owner) {
    return new CreateRequest(
        UUID.randomUUID(), owner, new ItemStack(Material.DIAMOND), 5, 1_000, Duration.ofHours(1));
  }

  private CreateStatus await(CompletableFuture<dev.orderhub.api.OrderService.CreateResult> f) {
    long end = System.nanoTime() + 15_000_000_000L;
    while (!f.isDone() && System.nanoTime() < end) {
      server.getScheduler().waitAsyncTasksFinished();
      server.getScheduler().performOneTick();
    }
    return f.orTimeout(1, TimeUnit.SECONDS).join().status();
  }

  private List<String> auditActors(String action) {
    List<String> actors = new java.util.ArrayList<>();
    try (var c = plugin.dataSource().getConnection();
        var ps = c.prepareStatement("SELECT actor FROM audit_log WHERE action = ?")) {
      ps.setString(1, action);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          actors.add(rs.getString(1));
        }
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    return actors;
  }

  @Test
  void anOfflineOwnerWithoutBypassIsRejected() {
    UUID offline = UUID.randomUUID();
    assertThat(await(api.orders().createOrder(request(offline))))
        .isEqualTo(CreateStatus.NO_PERMISSION);
    assertThat(auditActors("api.order.create")).isNotEmpty();
  }

  @Test
  void anOnlinePlayerWithoutTheCreatePermissionIsRejected() {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.create", false);
    assertThat(await(api.orders().createOrder(request(player.getUniqueId()))))
        .isEqualTo(CreateStatus.NO_PERMISSION);
  }

  @Test
  void aPermittedPlayerReachesTheEngineAndIsAuditedWithTheCallingPlugin() {
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.create", true);
    // Ohne Vault ist die Economy nicht verfuegbar: der Aufruf erreicht die Engine, scheitert dort.
    assertThat(await(api.orders().createOrder(request(player.getUniqueId()))))
        .isEqualTo(CreateStatus.ECONOMY_UNAVAILABLE);
    assertThat(auditActors("api.order.create")).anyMatch(a -> a.startsWith("plugin:"));
  }

  @Test
  void theExplicitBypassSkipsTheRightsCheckForOfflineOwners() {
    UUID offline = UUID.randomUUID();
    assertThat(await(api.orders().createOrder(request(offline), new CreateOptions(true))))
        .isEqualTo(CreateStatus.ECONOMY_UNAVAILABLE);
  }

  @Test
  void accessDecisionUsesTheTierLimitAndFeeBypass() {
    Limits limits =
        new Limits(1, 1_000_000, 100, 3, List.of(new Limits.Tier("orderhub.limit.10", 10)));
    Set<String> perms = Set.of("orderhub.create", "orderhub.limit.10", "orderhub.bypass.fee");
    ApiAccess full = ApiAccess.forPlayer(perms::contains, limits);
    assertThat(full.allowed()).isTrue();
    assertThat(full.maxOpenOrders()).isEqualTo(10);
    assertThat(full.feeExempt()).isTrue();
    ApiAccess basic = ApiAccess.forPlayer(Set.of("orderhub.create")::contains, limits);
    assertThat(basic.maxOpenOrders()).isEqualTo(3);
    assertThat(basic.feeExempt()).isFalse();
    assertThat(ApiAccess.forPlayer(s -> false, limits).allowed()).isFalse();
    assertThat(ApiAccess.bypass(limits).maxOpenOrders()).isEqualTo(3);
  }
}
