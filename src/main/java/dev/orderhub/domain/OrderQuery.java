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
 */
public record OrderQuery(
    UUID owner,
    String itemKey,
    String category,
    OrderStatus status,
    Sort sort,
    int offset,
    int limit) {

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
    limit = Math.max(1, Math.min(limit, 100));
    sort = sort == null ? Sort.NEWEST : sort;
    status = status == null ? OrderStatus.ACTIVE : status;
  }

  public static OrderQuery firstPage() {
    return new OrderQuery(null, null, null, null, null, 0, 45);
  }
}
