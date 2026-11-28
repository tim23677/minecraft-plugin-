package dev.orderhub.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Gespeicherte Auftragsvorlage eines Spielers. Beim erneuten Erstellen gelten die dann aktuellen
 * Grenzen, Gebuehren und Sperrlisten, nicht die vom Speichern.
 *
 * @since 2.0.0
 */
public record OrderTemplate(
    long id,
    UUID owner,
    String name,
    ItemSnapshot item,
    long unitPrice,
    int quantity,
    int minDelivery,
    int lifetimeHours,
    OrderOptions options,
    boolean autoRenew,
    long createdAt) {

  public OrderTemplate {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(options, "options");
    if (name.isBlank() || name.length() > 32) {
      throw new IllegalArgumentException("name must be 1..32 characters");
    }
  }
}
