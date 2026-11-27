package dev.orderhub.item;

/** Grund, warum ein Item nicht fuer Auftrag oder Lieferung zugelassen ist. */
public enum RejectionReason {
  /** Kein Item oder AIR. */
  EMPTY,
  /** Material steht auf der Sperrliste. */
  BLOCKED_MATERIAL,
  /** Eigener Name. */
  CUSTOM_NAME,
  /** Lore vorhanden. */
  LORE,
  /** Verzauberungen (auch gespeicherte, z. B. Buecher). */
  ENCHANTED,
  /** Schaden groesser 0. */
  DAMAGED,
  /** Inhalt in Container, Bundle, Buch oder geladener Armbrust. */
  CONTAINER_CONTENTS,
  /** Datenkomponente, die weder Standard noch ausdruecklich erlaubt ist. */
  UNEXPECTED_COMPONENT,
  /** Interner Fehler beim Dekodieren der geforderten Vorlage (nur bei matches). */
  INVALID_REQUIREMENT
}
