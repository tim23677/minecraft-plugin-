package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {

  private static final PlainTextComponentSerializer PLAIN =
      PlainTextComponentSerializer.plainText();

  private Messages load(String lang, Path dir) {
    Messages m = new Messages();
    m.load(lang, dir, getClass().getClassLoader());
    return m;
  }

  @Test
  void germanAndEnglishDefineTheSameKeys(@TempDir Path dir) {
    assertThat(load("de", dir).keys()).containsExactlyInAnyOrderElementsOf(load("en", dir).keys());
  }

  @Test
  void placeholdersAreSubstitutedAndPlayerInputIsNotParsed(@TempDir Path dir) {
    Messages m = load("en", dir);
    String text = PLAIN.serialize(m.get("create.cancelled"));
    assertThat(text).isNotBlank();
    String evil =
        PLAIN.serialize(m.get("general.invalid-number", Messages.text("input", "<red>boom")));
    assertThat(evil).contains("<red>boom");
  }

  @Test
  void unknownKeyFallsBackToKey(@TempDir Path dir) {
    assertThat(load("en", dir).raw("no.such.key")).isEqualTo("no.such.key");
  }

  @Test
  void unknownLanguageFallsBackToEnglish(@TempDir Path dir) {
    Messages m = load("xx", dir);
    assertThat(m.raw("general.reloaded")).contains("reloaded");
  }

  @Test
  void aKeyMissingInTheChosenLanguageFallsBackToEnglish(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(Messages.fileName("xx")),
        "general:\n  reloaded: \"<green>custom reloaded\"\n",
        StandardCharsets.UTF_8);
    Messages m = load("xx", dir);
    assertThat(m.raw("general.reloaded")).isEqualTo("<green>custom reloaded");
    assertThat(m.raw("general.busy")).isEqualTo(load("en", dir).raw("general.busy"));
  }

  @Test
  void aBrokenUserFileKeepsTheBundledTexts(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(Messages.fileName("de")), "general: [unclosed", StandardCharsets.UTF_8);
    assertThat(load("de", dir).raw("general.busy")).isNotEqualTo("general.busy");
  }

  @Test
  void everyKeyUsedInTheCodeExistsInBothLanguages(@TempDir Path dir) throws IOException {
    Pattern call =
        Pattern.compile("(?:\\.|\\b)(?:get|prefixed|raw)\\(\\s*\"([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"");
    Set<String> used = new HashSet<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        Matcher matcher = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
          used.add(matcher.group(1));
        }
      }
    }
    assertThat(used).isNotEmpty();
    assertThat(load("en", dir).keys()).containsAll(used);
    assertThat(load("de", dir).keys()).containsAll(used);
  }
}
