package dev.orderhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.orderhub.testsupport.PluginReady;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Lebenszyklus der Datenbank: asynchroner Start, klarer Abbruch, Schliessen beim Disable. */
class OrderHubLifecycleTest {

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
  void onEnableReturnsBeforeTheDatabaseIsReadyAndCommandsSayStarting() {
    // Der Server-Thread hat die Datenbank nicht geoeffnet: Aktivierung ist durch, bereit noch
    // nicht.
    assertThat(plugin.isEnabled()).isTrue();
    assertThat(plugin.isReady()).isFalse();
    assertThat(plugin.dataSource()).isNull();
    PlayerMock player = server.addPlayer();
    player.addAttachment(plugin, "orderhub.use", true);
    player.performCommand("order");
    assertThat(player.nextMessage()).containsIgnoringCase("starting");
  }

  @Test
  void becomesReadyAndExposesTheDataSource() {
    assertThat(PluginReady.await(server, plugin)).isTrue();
    assertThat(plugin.dataSource()).isInstanceOf(HikariDataSource.class);
    assertThat(((HikariDataSource) plugin.dataSource()).isClosed()).isFalse();
  }

  @Test
  void disableClosesTheDataSourceAfterCancellingTasks() {
    assertThat(PluginReady.await(server, plugin)).isTrue();
    HikariDataSource pool = (HikariDataSource) plugin.dataSource();
    server.getPluginManager().disablePlugin(plugin);
    assertThat(pool.isClosed()).isTrue();
    assertThat(plugin.isEnabled()).isFalse();
  }

  @Test
  void aFailingDatabaseDisablesThePluginWithoutWiring() throws IOException {
    assertThat(PluginReady.await(server, plugin)).isTrue();
    server.getPluginManager().disablePlugin(plugin);
    File config = new File(plugin.getDataFolder(), "config.yml");
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
    yaml.set("storage.type", "mariadb");
    yaml.set("storage.mariadb.password-env", "ORDERHUB_TEST_VARIABLE_THAT_DOES_NOT_EXIST");
    yaml.save(config);
    server.getPluginManager().enablePlugin(plugin);
    assertThat(PluginReady.await(server, plugin)).isFalse();
    assertThat(plugin.isEnabled()).isFalse();
    assertThat(plugin.isReady()).isFalse();
  }
}
