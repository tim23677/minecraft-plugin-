package dev.orderhub.economy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Architekturregel: org.bukkit und Vault nur in economy.vault. */
class NoBukkitInEngineTest {

  private static final Path ROOT = Path.of("src/main/java/dev/orderhub");

  @Test
  void enginePackagesAreFreeOfBukkitAndVault() throws IOException {
    for (String pkg :
        List.of(
            "economy", "tx", "service", "config", "domain", "money", "storage", "market",
            "discord")) {
      Path dir = ROOT.resolve(pkg);
      try (Stream<Path> files = Files.walk(dir)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          if (file.startsWith(ROOT.resolve("economy").resolve("vault"))) {
            continue;
          }
          String source = Files.readString(file);
          assertThat(source).as(file.toString()).doesNotContain("org.bukkit");
          assertThat(source).as(file.toString()).doesNotContain("net.milkbowl");
        }
      }
    }
  }

  @Test
  void vaultTypesAreIsolatedInTheVaultPackage() throws IOException {
    try (Stream<Path> files = Files.walk(ROOT)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        if (Files.readString(file).contains("net.milkbowl")) {
          assertThat(file.toString()).contains("/economy/vault/");
        }
      }
    }
  }
}
