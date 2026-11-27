package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class NotifierTest {

  private ServerMock server;
  private PlayerMock player;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    player = server.addPlayer();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void displayParsingFallsBackToChat() {
    assertThat(Notifier.Display.parse("actionbar")).isEqualTo(Notifier.Display.ACTIONBAR);
    assertThat(Notifier.Display.parse(" TITLE ")).isEqualTo(Notifier.Display.TITLE);
    assertThat(Notifier.Display.parse("chat")).isEqualTo(Notifier.Display.CHAT);
    assertThat(Notifier.Display.parse("nonsense")).isEqualTo(Notifier.Display.CHAT);
    assertThat(Notifier.Display.parse(null)).isEqualTo(Notifier.Display.CHAT);
  }

  @Test
  void chatDisplaySendsAChatMessage() {
    Notifier.show(player, Component.text("hello"), Notifier.Display.CHAT, "");
    assertThat(PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage()))
        .isEqualTo("hello");
  }

  @Test
  void actionbarAndTitleDoNotWriteToChatAndDoNotFail() {
    assertThatCode(() -> Notifier.show(player, Component.text("a"), Notifier.Display.ACTIONBAR, ""))
        .doesNotThrowAnyException();
    assertThatCode(() -> Notifier.show(player, Component.text("t"), Notifier.Display.TITLE, ""))
        .doesNotThrowAnyException();
    assertThat(player.nextComponentMessage()).isNull();
  }

  @Test
  void anInvalidSoundKeyKeepsTheMessageAndDoesNotThrow() {
    assertThatCode(
            () -> Notifier.show(player, Component.text("x"), Notifier.Display.CHAT, "NOT A KEY!!"))
        .doesNotThrowAnyException();
    assertThat(player.nextComponentMessage()).isNotNull();
  }

  @Test
  void aValidSoundKeyIsAccepted() {
    assertThatCode(() ->
            Notifier.show(
                player, Component.text("x"), Notifier.Display.CHAT, "entity.experience_orb.pickup"))
        .doesNotThrowAnyException();
  }
}
