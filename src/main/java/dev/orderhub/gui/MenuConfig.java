package dev.orderhub.gui;

/**
 * Konfiguration des Menue-Systems.
 *
 * @param debounceMillis Mindestabstand zwischen zwei Button-Klicks je Spieler in Millisekunden, 0
 *     schaltet die Entprellung ab
 */
public record MenuConfig(long debounceMillis) {

  public static final long DEFAULT_DEBOUNCE_MILLIS = 150;

  public MenuConfig {
    if (debounceMillis < 0) {
      throw new IllegalArgumentException("debounceMillis must be >= 0");
    }
  }

  public static MenuConfig defaults() {
    return new MenuConfig(DEFAULT_DEBOUNCE_MILLIS);
  }
}
