package dev.orderhub.domain;

import java.util.Objects;
import java.util.UUID;

/** Eingabe zum Anlegen eines Auftrags (vor Vergabe von Id, Version und Zeitstempeln). */
public record NewOrder(
    UUID owner,
    String serverId,
    ItemSnapshot item,
    long unitPrice,
    int quantity,
    int minDelivery,
    long expiresAt,
    boolean autoRenew,
    String category) {

  public NewOrder {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(serverId, "serverId");
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(category, "category");
    if (quantity <= 0 || minDelivery < 1 || minDelivery > quantity) {
      throw new IllegalArgumentException("invalid quantity");
    }
    if (unitPrice <= 0) {
      throw new IllegalArgumentException("unitPrice must be positive");
    }
  }

  /** Escrow, der fuer diesen Auftrag zu reservieren ist. */
  public long escrow() {
    return dev.orderhub.money.Money.multiply(unitPrice, quantity);
  }
}
