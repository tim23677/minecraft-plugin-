package dev.orderhub.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Liefert Spielertexte ueber Message-Keys. Die Implementierung (lang-Dateien, MiniMessage) folgt
 * spaeter; im Code stehen nur Keys.
 */
@FunctionalInterface
public interface MessageSource {

  Component get(String key, TagResolver... placeholders);

  /** Platzhalter bis zur Anbindung der lang-Dateien: gibt den Key selbst als Text zurueck. */
  static MessageSource keyEcho() {
    return (key, placeholders) -> Component.text(key);
  }
}
