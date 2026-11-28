package dev.orderhub.domain;

import java.util.UUID;

/**
 * Suchfilter fuer Auftraege. {@code null}-Felder bedeuten "kein Filter".
 *
 * @param owner nur Auftraege dieses Spielers
 * @param itemKey nur dieses Item
 * @param category nur diese Kategorie
 * @param status nur dieser Status (Standard: ACTIVE)
 * @param sort Sortierung
 * @param offset Start der Seite
 * @param limit Seitengroesse, hart auf 100 begrenzt
 * @param text Suchtext im Itemnamen (wird normalisiert), {@code null} = kein Filter
 * @param minUnitPrice nur Auftraege mit mindestens diesem Stueckpreis, {@code null} = kein Filter
 * @param maxUnitPrice nur Auftraege mit hoechstens diesem Stueckpreis, {@code null} = kein Filter
 * @param after Keyset-Cursor der letzten Zeile der vorigen Seite, {@code null} = erste Seite. Mit
 *     Cursor muss {@code offset} 0 sein.
 */
public record OrderQuery(
    UUID owner,
    String itemKey,
    String category,
    OrderStatus status,
    Sort sort,
    int offset,
    int limit,
    String text,
    Cursor after,
    Long minUnitPrice,
    Long maxUnitPrice) {

  /** Ohne Preisfilter. */
  public OrderQuery(
      UUID owner,
      String itemKey,
      String category,
      OrderStatus status,
      Sort sort,
      int offset,
      int limit,
      String text,
      Cursor after) {
    this(owner, itemKey, category, status, sort, offset, limit, text, after, null, null);
  }

  /** Ohne Textsuche und Cursor (Offset-Paginierung). */
  public OrderQuery(
      UUID owner,
      String itemKey,
      String category,
      OrderStatus status,
      Sort sort,
      int offset,
      int limit) {
    this(owner, itemKey, category, status, sort, offset, limit, null, null, null, null);
  }

  /**
   * Position in der Sortierung. Alle Schluessel sind ganzzahlig, damit der Vergleich in beiden
   * Datenbanken exakt ist.
   *
   * @param k1 erster Sortierschluessel (je nach Sortierung)
   * @param k2 zweiter Sortierschluessel, sonst 0
   * @param id Id der Zeile als letzter Schluessel
   */
  public record Cursor(long k1, long k2, long id) {

    /** Cursor der Zeile {@code o} fuer {@code sort}. */
    public static Cursor of(Sort sort, Order o) {
      return switch (sort) {
        case NEWEST -> new Cursor(o.createdAt(), 0, o.id());
        case HIGHEST_PRICE, LOWEST_PRICE -> new Cursor(o.unitPrice(), 0, o.id());
        case ALMOST_COMPLETED -> new Cursor(o.fillPermille(), 0, o.id());
        case HIGHEST_DEMAND -> new Cursor(o.remaining(), o.escrowRemaining(), o.id());
      };
    }
  }

  /** Maximale Laenge des Suchtexts. */
  public static final int MAX_TEXT = 64;

  /** Normalisiert Suchtext: klein, Leerzeichen zu Unterstrich, nur a-z, 0-9, Unterstrich. */
  public static String normalizeText(String raw) {
    if (raw == null) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (char c : raw.trim().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
        out.append(c);
      } else if (c == ' ' || c == '-') {
        out.append('_');
      }
      if (out.length() >= MAX_TEXT) {
        break;
      }
    }
    return out.isEmpty() ? null : out.toString();
  }

  /** Sortieroptionen. */
  public enum Sort {
    NEWEST,
    HIGHEST_PRICE,
    LOWEST_PRICE,
    ALMOST_COMPLETED,
    HIGHEST_DEMAND
  }

  public OrderQuery {
    if (offset < 0) {
      throw new IllegalArgumentException("offset");
    }
    if (after != null && offset != 0) {
      throw new IllegalArgumentException("offset must be 0 with a cursor");
    }
    if ((minUnitPrice != null && minUnitPrice < 0) || (maxUnitPrice != null && maxUnitPrice < 0)) {
      throw new IllegalArgumentException("price filter must not be negative");
    }
    if (minUnitPrice != null && maxUnitPrice != null && minUnitPrice > maxUnitPrice) {
      throw new IllegalArgumentException("minUnitPrice must not exceed maxUnitPrice");
    }
    text = normalizeText(text);
    limit = Math.max(1, Math.min(limit, 100));
    sort = sort == null ? Sort.NEWEST : sort;
    status = status == null ? OrderStatus.ACTIVE : status;
  }

  public static OrderQuery firstPage() {
    return new OrderQuery(null, null, null, null, null, 0, 45);
  }
}
