package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Alle mitgelieferten Sprachdateien haben dieselben Schluessel, dieselben Platzhalter je Schluessel
 * und lassen sich als MiniMessage lesen. Eine neue Sprache muss hier eingetragen werden.
 */
class AllLanguagesTest {

  static final List<String> LANGUAGES =
      List.of("en", "de", "fr", "es", "pl", "pt-br", "ru", "tr", "zh-cn");

  /**
   * Hilfetexte mit Befehlssyntax: Die Platzhalter in spitzen Klammern sind dort Hinweise fuer den
   * Spieler ("<Grund>"), keine Variablen. Sie duerfen uebersetzt sein.
   */
  private static final java.util.Set<String> SYNTAX_HINTS =
      java.util.Set.of(
          "general.help",
          "general.usage-create",
          "admin.help",
          "admin.export-usage",
          "admin.review-usage");

  private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9-]*)>");

  /** Platzhalter, die MiniMessage selbst kennt (Farben und Formate), nicht von OrderHub. */
  private static final java.util.Set<String> MINIMESSAGE_TAGS =
      java.util.Set.of(
          "red",
          "green",
          "yellow",
          "gray",
          "white",
          "aqua",
          "gold",
          "dark_gray",
          "dark_red",
          "dark_green",
          "blue",
          "light_purple",
          "black",
          "bold",
          "italic",
          "reset",
          "gradient");

  private static Map<String, String> load(String language) throws Exception {
    try (InputStream in =
        AllLanguagesTest.class
            .getClassLoader()
            .getResourceAsStream("messages_" + language + ".yml")) {
      assertThat(in).as("messages_%s.yml is bundled", language).isNotNull();
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      Map<String, String> flat = new TreeMap<>();
      for (String key : yaml.getKeys(true)) {
        if (yaml.get(key) instanceof String text) {
          flat.put(key, text);
        }
      }
      return flat;
    }
  }

  private static List<String> placeholders(String text) {
    List<String> found = new ArrayList<>();
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      if (!MINIMESSAGE_TAGS.contains(m.group(1))) {
        found.add(m.group(1));
      }
    }
    found.sort(String::compareTo);
    return found;
  }

  @Test
  void everyLanguageHasExactlyTheKeysOfEnglish() throws Exception {
    Map<String, String> english = load("en");
    for (String language : LANGUAGES) {
      assertThat(load(language).keySet())
          .as("keys of %s", language)
          .containsExactlyElementsOf(english.keySet());
    }
  }

  @Test
  void everyKeyUsesTheSamePlaceholdersAsEnglish() throws Exception {
    Map<String, String> english = load("en");
    for (String language : LANGUAGES) {
      Map<String, String> table = load(language);
      for (var entry : english.entrySet()) {
        if (SYNTAX_HINTS.contains(entry.getKey())) {
          continue;
        }
        assertThat(placeholders(table.get(entry.getKey())))
            .as("placeholders of %s in %s", entry.getKey(), language)
            .isEqualTo(placeholders(entry.getValue()));
      }
    }
  }

  @Test
  void everyTextParsesAsMiniMessageAndIsNotEmpty() throws Exception {
    for (String language : LANGUAGES) {
      for (var entry : load(language).entrySet()) {
        assertThat(entry.getValue()).as("%s %s", language, entry.getKey()).isNotBlank();
        MiniMessage.miniMessage().deserialize(entry.getValue(), Placeholder.unparsed("x", "x"));
      }
    }
  }

  @Test
  void otherLanguagesAreActuallyTranslated() throws Exception {
    Map<String, String> english = load("en");
    for (String language : LANGUAGES) {
      if (language.equals("en")) {
        continue;
      }
      Map<String, String> table = load(language);
      long same =
          english.keySet().stream().filter(k -> english.get(k).equals(table.get(k))).count();
      // Namen wie "OrderHub" oder reine Platzhalter duerfen gleich bleiben, der Rest nicht.
      assertThat(same).as("untranslated keys in %s", language).isLessThan(english.size() / 8);
    }
  }
}
