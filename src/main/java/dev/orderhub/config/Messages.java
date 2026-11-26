package dev.orderhub.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Spielertexte aus {@code lang/<sprache>.yml} (MiniMessage). Reihenfolge der Suche: Datei im
 * Plugin-Ordner, mitgelieferte Datei derselben Sprache, mitgelieferte englische Datei, der
 * Schluessel selbst. Der Wechsel beim Reload ist atomar ueber den Austausch des unveraenderlichen
 * Schnappschusses.
 */
public final class Messages {

  private static final MiniMessage MM = MiniMessage.miniMessage();

  private volatile Map<String, String> table = Map.of();

  /** Laedt die Texte neu und tauscht sie atomar aus. */
  public void load(String language, Path dataDir, ClassLoader resources) {
    Map<String, String> next = new ConcurrentHashMap<>();
    readBundled(resources, "en", next);
    if (!"en".equals(language)) {
      readBundled(resources, language, next);
    }
    Path custom = dataDir.resolve("lang").resolve(language + ".yml");
    if (Files.isRegularFile(custom)) {
      try (Reader reader = Files.newBufferedReader(custom, StandardCharsets.UTF_8)) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(reader);
        flatten(yaml, next);
      } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
        // Eine kaputte Benutzerdatei darf das Plugin nicht lahmlegen: Standardtexte bleiben aktiv.
      }
    }
    this.table = Map.copyOf(next);
  }

  private static void readBundled(ClassLoader cl, String language, Map<String, String> into) {
    try (InputStream in = cl.getResourceAsStream("lang/" + language + ".yml")) {
      if (in == null) {
        return;
      }
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      flatten(yaml, into);
    } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IllegalStateException("bundled language file broken: " + language, e);
    }
  }

  private static void flatten(YamlConfiguration yaml, Map<String, String> into) {
    for (String key : yaml.getKeys(true)) {
      Object value = yaml.get(key);
      if (value instanceof String s) {
        into.put(key, s);
      }
    }
  }

  /** Rohtext zu einem Schluessel, oder der Schluessel selbst. */
  public String raw(String key) {
    return table.getOrDefault(key, key);
  }

  /** Komponente mit Platzhaltern. Spielereingaben gehen nur als unparsed-Text hinein. */
  public Component get(String key, TagResolver... resolvers) {
    return MM.deserialize(raw(key), resolvers);
  }

  /** Wie {@link #get}, mit vorangestelltem Prefix. */
  public Component prefixed(String key, TagResolver... resolvers) {
    return MM.deserialize(raw("prefix")).append(get(key, resolvers));
  }

  /** Platzhalter, dessen Wert nie als MiniMessage interpretiert wird (sicher fuer Spielernamen). */
  public static TagResolver text(String name, String value) {
    return Placeholder.unparsed(name, value);
  }

  /** Platzhalter fuer eine Zahl. */
  public static TagResolver number(String name, long value) {
    return Placeholder.unparsed(name, Long.toString(value));
  }

  /** Alle bekannten Schluessel (fuer Tests auf Vollstaendigkeit). */
  public java.util.Set<String> keys() {
    return table.keySet();
  }
}
