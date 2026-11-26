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
}
