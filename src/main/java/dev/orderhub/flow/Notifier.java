package dev.orderhub.flow;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * Zeigt eine Benachrichtigung im Chat, in der Actionbar oder als Titel und spielt optional einen
 * Ton. Entity-Kontext.
 *
 * @since 2.0.0
 */
public final class Notifier {

  /** Anzeigeart, einstellbar ueber {@code notifications.display}. */
  public enum Display {
    CHAT,
    ACTIONBAR,
    TITLE;

    public static Display parse(String raw) {
      if (raw != null) {
        for (Display d : values()) {
          if (d.name().equalsIgnoreCase(raw.trim())) {
            return d;
          }
        }
      }
      return CHAT;
    }
  }

  private Notifier() {}

  /**
   * @param soundKey Sound-Schluessel wie {@code entity.experience_orb.pickup}, leer = kein Ton
   */
  public static void show(Player player, Component message, Display display, String soundKey) {
    switch (display) {
      case ACTIONBAR -> player.sendActionBar(message);
      case TITLE -> player.showTitle(Title.title(Component.empty(), message));
      default -> player.sendMessage(message);
    }
    if (soundKey != null && !soundKey.isBlank()) {
      try {
        player.playSound(
            Sound.sound(
                Key.key(soundKey.trim().toLowerCase(java.util.Locale.ROOT)),
                Sound.Source.MASTER,
                1f,
                1f));
      } catch (RuntimeException e) {
        // ungueltiger Schluessel: Meldung bleibt, nur der Ton entfaellt
      }
    }
  }
}
