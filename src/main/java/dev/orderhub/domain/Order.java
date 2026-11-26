package dev.orderhub.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistierter Auftrag. Invariante fuer ACTIVE und PAUSED: {@code escrowRemaining == unitPrice *
 * (quantityTotal - quantityFilled)}. Fuer beendete Auftraege ist {@code escrowRemaining == 0}.
 */
public record Order(
    long id,
    UUID owner,
    String serverId,
    ItemSnapshot item,
    long unitPrice,
    int quantityTotal,
    int quantityFilled,
    int minDelivery,
    long escrowRemaining,
    OrderStatus status,
    long createdAt,
    long expiresAt,
    long updatedAt,
    long version,
    boolean autoRenew,
    String category) {

  public Order {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(serverId, "serverId");
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(category, "category");
    if (quantityTotal <= 0 || quantityFilled < 0 || quantityFilled > quantityTotal) {
      throw new IllegalArgumentException("invalid quantities");
    }
    if (minDelivery < 1) {
      throw new IllegalArgumentException("minDelivery must be >= 1");
    }
    if (unitPrice < 0 || escrowRemaining < 0) {
      throw new IllegalArgumentException("negative money");
    }
  }

  /** Noch nicht gelieferte Menge. */
  public int remaining() {
    return quantityTotal - quantityFilled;
  }

  /** Erwarteter Escrow aus Restmenge und Stueckpreis. */
  public long expectedEscrow() {
    return Math.multiplyExact(unitPrice, (long) remaining());
  }

  /** Pruefung der Escrow-Invariante. */
  public boolean escrowConsistent() {
    return status.isTerminal() ? escrowRemaining == 0 : escrowRemaining == expectedEscrow();
  }

  public Order withVersionAndStatus(long newVersion, OrderStatus newStatus, long now) {
    return new Order(
        id,
        owner,
        serverId,
        item,
        unitPrice,
        quantityTotal,
        quantityFilled,
        minDelivery,
        escrowRemaining,
        newStatus,
        createdAt,
        expiresAt,
        now,
        newVersion,
        autoRenew,
        category);
  }

  /** Lieferung von {@code n} Stueck: Menge und Escrow werden gemeinsam fortgeschrieben. */
  public Order afterDelivery(int n, long now) {
    if (n < 1 || n > remaining()) {
      throw new IllegalArgumentException("delivery out of range: " + n);
    }
    int filled = quantityFilled + n;
    long escrow = escrowRemaining - Math.multiplyExact(unitPrice, (long) n);
    OrderStatus next = filled == quantityTotal ? OrderStatus.COMPLETED : status;
    return new Order(
        id,
        owner,
        serverId,
        item,
        unitPrice,
        quantityTotal,
        filled,
        minDelivery,
        escrow,
        next,
        createdAt,
        expiresAt,
        now,
        version + 1,
        autoRenew,
        category);
  }
}
