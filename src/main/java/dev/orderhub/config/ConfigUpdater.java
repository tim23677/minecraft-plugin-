package dev.orderhub.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Ergaenzt eine vorhandene YAML-Datei um neue Schluessel der mitgelieferten Standarddatei. Vorhandene
 * Werte werden nie ueberschrieben. Vor jeder Aenderung entsteht eine Sicherung {@code <datei>.bak}.
 */
public final class ConfigUpdater {

  private ConfigUpdater() {}

  /**
   * @return Anzahl ergaenzter Schluessel
   */
  public static int mergeDefaults(Path target, InputStream bundledDefaults) throws IOException {
    YamlConfiguration defaults = new YamlConfiguration();
    try (Reader reader = new InputStreamReader(bundledDefaults, StandardCharsets.UTF_8)) {
      defaults.load(reader);
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IOException("bundled defaults are invalid", e);
    }
    if (!Files.exists(target)) {
      defaults.save(target.toFile());
      return 0;
    }
    YamlConfiguration current = new YamlConfiguration();
    try {
      current.load(target.toFile());
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IOException("existing config is not valid YAML: " + target, e);
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
    if (added > 0) {
      Files.copy(
          target,
          target.resolveSibling(target.getFileName() + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
      current.save(target.toFile());
    }
    return added;
  }
}
