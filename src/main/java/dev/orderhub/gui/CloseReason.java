package dev.orderhub.gui;

/** Warum ein Menue beendet wurde. */
public enum CloseReason {
  /** Spieler schliesst das Inventar. */
  PLAYER_CLOSE(true, false),
  /** Spieler wechselt die Welt. */
  WORLD_CHANGE(true, false),
  /** Ein anderes Menue ersetzt dieses. */
  REPLACED(true, false),
  /** Plugin wird deaktiviert. */
  PLUGIN_DISABLE(true, false),
  /** Spieler trennt die Verbindung. */
  DISCONNECT(false, true),
  /** Spieler wird gekickt. */
  KICK(false, true),
  /** Spieler stirbt. */
  DEATH(false, true);

  private final boolean returnsToPlayer;
  private final boolean abandoned;

  CloseReason(boolean returnsToPlayer, boolean abandoned) {
    this.returnsToPlayer = returnsToPlayer;
    this.abandoned = abandoned;
  }

  /** Items duerfen zuerst ins Spielerinventar (sonst nur ueber den ReturnSink). */
  public boolean returnsToPlayer() {
    return returnsToPlayer;
  }

  /** Spieler verlaesst die Sitzung bzw. kann das Inventar nicht mehr sicher aufnehmen. */
  public boolean abandoned() {
    return abandoned;
  }
}
