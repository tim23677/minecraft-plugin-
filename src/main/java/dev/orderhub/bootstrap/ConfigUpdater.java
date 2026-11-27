package dev.orderhub.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Ergaenzt eine vorhandene YAML-Datei um neue Schluessel der mitgelieferten Standarddatei.
 * Vorhandene Werte werden nie ueberschrieben. Vor jeder Aenderung entsteht eine Sicherung {@code
 * <datei>.bak}. Dateien mit einem Versionsschluessel ({@code config-version}, {@code menu-version})
 * werden ueber Migrationsschritte von Version zu Version gebracht: entfernte Optionen werden
 * aufgeraeumt, nie ueberschreibt ein Schritt Werte der Benutzerin oder des Benutzers.
 */
public final class ConfigUpdater {

  private ConfigUpdater() {}

  /** Ein Migrationsschritt auf {@code toVersion}. */
  public record Migration(int toVersion, java.util.function.Consumer<YamlConfiguration> step) {}

  /**
   * Ergebnis eines Laufs.
   *
   * @param newerThanPlugin die Datei hat eine neuere Version als dieses Plugin kennt: unveraendert
   */
  public record Result(
      int added, int removed, int fromVersion, int toVersion, boolean newerThanPlugin) {}

  /** Entfernte Optionen von config-version 1 (Builds vor 1.0.0), die nie eine Wirkung hatten. */
  private static final List<String> REMOVED_IN_V2 =
      List.of(
          "cache.sync-interval-seconds",
          "market.recommendation-window-days",
          "notifications.batch-window-seconds",
          "reputation",
          "sounds",
          "commands");

  private static final String ECONOMY_TIMEOUT_KEY = "economy.timeout-ms";
  private static final int OLD_ECONOMY_TIMEOUT_MS = 10_000;
  private static final int NEW_ECONOMY_TIMEOUT_MS = 3_000;

  /** Migrationsschritte je Dateiname. */
  public static List<Migration> migrationsFor(String fileName) {
    if (fileName.equals("config.yml")) {
      return List.of(
          new Migration(
              2,
              cfg -> {
                for (String key : REMOVED_IN_V2) {
                  cfg.set(key, null);
                }
              }),
          new Migration(
              3,
              cfg -> {
                // Nur der alte Standardwert wird gesenkt. Wer einen eigenen Wert gesetzt hat,
                // behaelt ihn.
                if (cfg.isSet(ECONOMY_TIMEOUT_KEY)
                    && cfg.getInt(ECONOMY_TIMEOUT_KEY) == OLD_ECONOMY_TIMEOUT_MS) {
                  cfg.set(ECONOMY_TIMEOUT_KEY, NEW_ECONOMY_TIMEOUT_MS);
                }
              }));
    }
    return List.of();
  }

  /** Versionsschluessel je Dateiname, {@code null} fuer Dateien ohne Version. */
  public static String versionKeyFor(String fileName) {
    return switch (fileName) {
      case "config.yml" -> "config-version";
      case "menus.yml" -> "menu-version";
      default -> null;
    };
  }

  /**
   * @return Anzahl ergaenzter Schluessel
   */
  public static int mergeDefaults(Path target, InputStream bundledDefaults) throws IOException {
    return update(target, bundledDefaults, null, List.of()).added();
  }

  /**
   * Bringt {@code target} auf den Stand der mitgelieferten Datei: Migrationsschritte bis zur
   * mitgelieferten Version, danach fehlende Schluessel ergaenzen. Vor jeder Aenderung entsteht eine
   * Sicherung.
   */
  public static Result update(
      Path target, InputStream bundledDefaults, String versionKey, List<Migration> migrations)
      throws IOException {
    YamlConfiguration defaults = new YamlConfiguration();
    try (Reader reader = new InputStreamReader(bundledDefaults, StandardCharsets.UTF_8)) {
      defaults.load(reader);
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IOException("bundled defaults are invalid", e);
    }
    int bundledVersion = versionKey == null ? 1 : defaults.getInt(versionKey, 1);
    if (!Files.exists(target)) {
      defaults.save(target.toFile());
      return new Result(0, 0, bundledVersion, bundledVersion, false);
    }
    YamlConfiguration current = new YamlConfiguration();
    try {
      current.load(target.toFile());
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IOException("existing config is not valid YAML: " + target, e);
    }
    int fromVersion = versionKey == null ? 1 : current.getInt(versionKey, 1);
    if (fromVersion > bundledVersion) {
      return new Result(0, 0, fromVersion, bundledVersion, true);
    }
    int removed = 0;
    boolean migrated = false;
    if (versionKey != null) {
      for (Migration m : migrations) {
        if (m.toVersion() > fromVersion && m.toVersion() <= bundledVersion) {
          long before = current.getKeys(true).size();
          m.step().accept(current);
          removed += (int) Math.max(0, before - current.getKeys(true).size());
          migrated = true;
        }
      }
      if (fromVersion != bundledVersion) {
        current.set(versionKey, bundledVersion);
        migrated = true;
      }
    }
    int added = 0;
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) {
        continue;
      }
      if (!current.contains(key)) {
        current.set(key, defaults.get(key));
        added++;
      }
    }
    if (added > 0 || migrated) {
      Files.copy(
          target,
          target.resolveSibling(target.getFileName() + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
      current.save(target.toFile());
    }
    return new Result(added, removed, fromVersion, bundledVersion, false);
  }
}
