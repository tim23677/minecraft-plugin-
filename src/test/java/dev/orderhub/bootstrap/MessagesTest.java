package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}
