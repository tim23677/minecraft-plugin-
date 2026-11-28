package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {

  private static ByteArrayInputStream yaml(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void addsMissingKeysWithoutOverwritingExistingValues(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(file, "a: 1\nnested:\n  x: 5\n");
    int added = ConfigUpdater.mergeDefaults(file, yaml("a: 99\nb: 2\nnested:\n  x: 0\n  y: 7\n"));
    assertThat(added).isEqualTo(2);
    YamlConfiguration result = YamlConfiguration.loadConfiguration(file.toFile());
    assertThat(result.getInt("a")).isEqualTo(1);
    assertThat(result.getInt("b")).isEqualTo(2);
    assertThat(result.getInt("nested.x")).isEqualTo(5);
    assertThat(result.getInt("nested.y")).isEqualTo(7);
    assertThat(dir.resolve("config.yml.bak")).exists();
  }

  @Test
  void createsFileWhenMissing(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("new.yml");
    assertThat(ConfigUpdater.mergeDefaults(file, yaml("a: 1\n"))).isZero();
    assertThat(file).exists();
  }

  @Test
  void leavesFileUntouchedWhenComplete(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("c.yml");
    Files.writeString(file, "a: 1\n");
    assertThat(ConfigUpdater.mergeDefaults(file, yaml("a: 2\n"))).isZero();
    assertThat(dir.resolve("c.yml.bak")).doesNotExist();
  }

  private static final String BUNDLED_V2 =
      "# Kopf\nconfig-version: 2\nfees:\n  # Gebuehr in Basispunkten\n  create-fee-bp: 100\nnew-option: 7\n";

  @Test
  void migratesVersionOneRemovesObsoleteOptionsAndKeepsUserValues(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(
        file,
        "config-version: 1\nfees:\n  # eigene Notiz\n  create-fee-bp: 250\n"
            + "sounds:\n  enabled: true\ncommands:\n  aliases: []\nreputation:\n  enabled: false\n"
            + "cache:\n  sync-interval-seconds: 3\n  max-entries: 99\n");
    var migrations = ConfigUpdater.migrationsFor("config.yml");
    var result = ConfigUpdater.update(file, yaml(BUNDLED_V2), "config-version", migrations);

    assertThat(result.fromVersion()).isEqualTo(1);
    assertThat(result.toVersion()).isEqualTo(2);
    assertThat(result.removed()).isPositive();
    YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file.toFile());
    assertThat(migrated.getInt("config-version")).isEqualTo(2);
    assertThat(migrated.getInt("fees.create-fee-bp")).isEqualTo(250); // Wert der Benutzerin bleibt
    assertThat(migrated.getInt("cache.max-entries")).isEqualTo(99);
    assertThat(migrated.contains("sounds")).isFalse();
    assertThat(migrated.contains("commands")).isFalse();
    assertThat(migrated.contains("reputation")).isFalse();
    assertThat(migrated.contains("cache.sync-interval-seconds")).isFalse();
    assertThat(migrated.getInt("new-option")).isEqualTo(7); // neue Option ergaenzt
    assertThat(dir.resolve("config.yml.bak")).exists();
    assertThat(Files.readString(dir.resolve("config.yml.bak"))).contains("sounds:");
    assertThat(Files.readString(file)).contains("# eigene Notiz"); // Kommentar bleibt erhalten
  }

  @Test
  void aSecondRunChangesNothing(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(
        file, "config-version: 1\nfees:\n  create-fee-bp: 100\nsounds:\n  enabled: true\n");
    var migrations = ConfigUpdater.migrationsFor("config.yml");
    ConfigUpdater.update(file, yaml(BUNDLED_V2), "config-version", migrations);
    String after = Files.readString(file);
    Files.delete(dir.resolve("config.yml.bak"));
    var again = ConfigUpdater.update(file, yaml(BUNDLED_V2), "config-version", migrations);
    assertThat(again.added()).isZero();
    assertThat(again.removed()).isZero();
    assertThat(Files.readString(file)).isEqualTo(after);
    assertThat(dir.resolve("config.yml.bak")).doesNotExist();
  }

  @Test
  void aFileFromANewerPluginIsLeftUntouched(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    String original = "config-version: 5\nsounds:\n  enabled: true\n";
    Files.writeString(file, original);
    var result =
        ConfigUpdater.update(
            file, yaml(BUNDLED_V2), "config-version", ConfigUpdater.migrationsFor("config.yml"));
    assertThat(result.newerThanPlugin()).isTrue();
    assertThat(Files.readString(file)).isEqualTo(original);
    assertThat(dir.resolve("config.yml.bak")).doesNotExist();
  }

  @Test
  void theBundledConfigVersionHasAMigrationPath() throws Exception {
    YamlConfiguration bundled = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      bundled.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
    }
    int version = bundled.getInt("config-version");
    assertThat(ConfigUpdater.migrationsFor("config.yml"))
        .extracting(ConfigUpdater.Migration::toVersion)
        .contains(version);
  }

  private static java.io.InputStream bundledConfig() {
    return ConfigUpdaterTest.class.getClassLoader().getResourceAsStream("config.yml");
  }

  @Test
  void versionThreeLowersTheOldDefaultEconomyTimeout(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(
        file, "config-version: 2\neconomy:\n  async-safe: false\n  timeout-ms: 10000\n");
    ConfigUpdater.update(
        file, bundledConfig(), "config-version", ConfigUpdater.migrationsFor("config.yml"));
    YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file.toFile());
    assertThat(migrated.getInt("economy.timeout-ms")).isEqualTo(3000);
    assertThat(migrated.getInt("config-version")).isEqualTo(3);
  }

  @Test
  void versionThreeKeepsACustomEconomyTimeout(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(file, "config-version: 2\neconomy:\n  timeout-ms: 7000\n");
    ConfigUpdater.update(
        file, bundledConfig(), "config-version", ConfigUpdater.migrationsFor("config.yml"));
    assertThat(YamlConfiguration.loadConfiguration(file.toFile()).getInt("economy.timeout-ms"))
        .isEqualTo(7000);
  }

  @Test
  void aServerThatAlreadyRunsVersionThreeKeepsTenThousandOnPurpose(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("config.yml");
    Files.writeString(file, "config-version: 3\neconomy:\n  timeout-ms: 10000\n");
    ConfigUpdater.update(
        file, bundledConfig(), "config-version", ConfigUpdater.migrationsFor("config.yml"));
    assertThat(YamlConfiguration.loadConfiguration(file.toFile()).getInt("economy.timeout-ms"))
        .isEqualTo(10000);
  }

  @Test
  void aFreshInstallGetsTheNewDefault(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("config.yml");
    ConfigUpdater.update(
        file, bundledConfig(), "config-version", ConfigUpdater.migrationsFor("config.yml"));
    assertThat(YamlConfiguration.loadConfiguration(file.toFile()).getInt("economy.timeout-ms"))
        .isEqualTo(3000);
  }
}
